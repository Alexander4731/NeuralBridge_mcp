package com.neuralbridge.companion.mcp

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.neuralbridge.companion.BuildConfig
import com.neuralbridge.companion.log.CommandLog
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class McpHttpServer(
    private val context: Context,
    private val toolHandler: McpToolHandler,
    private val port: Int = MCP_PORT
) {
    companion object {
        const val MCP_PORT = 7474
        const val MCP_HOST = "127.0.0.1"
        const val MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
        const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"
        private const val TAG = "McpHttpServer"
        private const val SERVER_NAME = "neuralbridge-android"
        private const val SERVER_VERSION = BuildConfig.VERSION_NAME
        internal const val SERVER_INSTRUCTIONS =
            "Prefer structured Accessibility data over screenshots. Start with android_get_ui_tree " +
                "or android_find_elements, act with selectors, then wait with android_wait_for_idle, " +
                "android_wait_for_element, or android_wait_for_gone. Use android_get_screen_context " +
                "only when combined context helps. Call android_screenshot only for canvases, images, " +
                "visual ambiguity, or explicit visual verification. Root is not exposed by " +
                "NeuralBridge; use Termux su -c separately when needed."
        private const val SCREEN_WAKE_LOCK_TIMEOUT_MS = 5L * 60 * 1000 // 5 minutes
    }

    private val authManager = McpAuthManager(context)

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    // Session tracking: sessionId → creation time. Capped to prevent unbounded growth.
    private val sessions = ConcurrentHashMap<String, Long>()
    private val MAX_SESSIONS = 100

    // HTTP activity tracking for UI connection status
    private val lastRequestTimestamp = AtomicLong(0L)

    // Screen wake lock: keeps screen on while MCP client is active
    @Volatile
    private var screenWakeLock: PowerManager.WakeLock? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        releaseScreenWakeLock()
        @Suppress("DEPRECATION")
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        screenWakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "NeuralBridge::McpClientScreen"
        ).apply { setReferenceCounted(false) }

        Log.i(TAG, "Starting MCP HTTP server on $MCP_HOST:$port")

        server = embeddedServer(CIO, port = port, host = MCP_HOST) {
            routing {
                get("/") {
                    call.respondText("NeuralBridge MCP Server v$SERVER_VERSION - POST /mcp")
                }

                get("/health") {
                    call.respondText(
                        "{\"status\":\"ok\",\"version\":\"$SERVER_VERSION\"}",
                        ContentType.Application.Json
                    )
                }

                // CORS preflight for browser-based MCP clients
                options("/mcp") {
                    val origin = call.request.headers[HttpHeaders.Origin]
                    if (origin == null || !McpRequestSecurity.isAllowedOrigin(origin)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@options
                    }
                    appendCorsHeaders(call, origin)
                    call.response.headers.append("Access-Control-Allow-Methods", "POST, OPTIONS")
                    call.response.headers.append(
                        "Access-Control-Allow-Headers",
                        listOf(
                            HttpHeaders.ContentType,
                            HttpHeaders.Authorization,
                            MCP_PROTOCOL_VERSION_HEADER,
                            MCP_SESSION_ID_HEADER
                        ).joinToString(", ")
                    )
                    call.respond(HttpStatusCode.NoContent)
                }

                post("/mcp") {
                    handleMcpPost(call)
                }

                // Static bearer tokens are configured directly in the MCP client.
                get("/.well-known/oauth-protected-resource") {
                    call.respondText(
                        "{\"error\":\"OAuth is not supported; configure the NeuralBridge bearer token\"}",
                        ContentType.Application.Json,
                        HttpStatusCode.NotFound
                    )
                }
                get("/.well-known/oauth-authorization-server") {
                    call.respondText("{}", ContentType.Application.Json, HttpStatusCode.NotFound)
                }
                get("/.well-known/openid-configuration") {
                    call.respondText("{}", ContentType.Application.Json, HttpStatusCode.NotFound)
                }
            }

        }.start(wait = false)

        Log.i(TAG, "MCP HTTP server started. URL: http://$MCP_HOST:$port/mcp")
    }

    private suspend fun handleMcpPost(call: ApplicationCall) {
        val origin = call.request.headers[HttpHeaders.Origin]
        if (!McpRequestSecurity.isAllowedOrigin(origin)) {
            call.respondText(
                "{\"error\":\"forbidden_origin\"}",
                ContentType.Application.Json,
                HttpStatusCode.Forbidden
            )
            return
        }

        origin?.let { appendCorsHeaders(call, it) }

        if (!authManager.validateAuthorizationHeader(call.request.headers[HttpHeaders.Authorization])) {
            call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer realm=\"NeuralBridge MCP\"")
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondText(
                "{\"error\":\"unauthorized\"}",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized
            )
            return
        }

        val protocolHeader = call.request.headers[MCP_PROTOCOL_VERSION_HEADER]
        if (!McpProtocolVersions.isSupportedHeader(protocolHeader)) {
            call.respondText(
                "{\"error\":\"unsupported_protocol_version\"}",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }

        lastRequestTimestamp.set(System.currentTimeMillis())
        screenWakeLock?.acquire(SCREEN_WAKE_LOCK_TIMEOUT_MS)

        // Parse request body
        val body = call.receiveText()
        val request = try {
            json.decodeFromString<JsonRpcRequest>(body)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse JSON-RPC request: ${e.message}")
            val errResp = errorResponse(null, JsonRpcErrorCodes.PARSE_ERROR, "Parse error: ${e.message}")
            call.respondText(
                json.encodeToString(JsonRpcResponse.serializer(), errResp),
                ContentType.Application.Json
            )
            return
        }

        Log.d(TAG, "MCP request: method=${request.method}, id=${request.id}")

        // Route by method
        var newSessionId: String? = null
        val response: JsonRpcResponse? = when (request.method) {
            "initialize" -> {
                newSessionId = createSession()
                handleInitialize(request)
            }
            "notifications/initialized" -> null  // client ACK, no response
            "ping" -> successResponse(request.id, buildJsonObject {})
            "tools/list" -> handleToolsList(request)
            "tools/call" -> handleToolsCall(request)
            else -> {
                Log.w(TAG, "Unknown MCP method: ${request.method}")
                errorResponse(request.id, JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: ${request.method}")
            }
        }

        if (response != null) {
            newSessionId?.let { call.response.headers.append(MCP_SESSION_ID_HEADER, it) }
            call.respondText(
                json.encodeToString(JsonRpcResponse.serializer(), response),
                ContentType.Application.Json
            )
        } else {
            call.respond(HttpStatusCode.Accepted)
        }
    }

    private fun handleInitialize(request: JsonRpcRequest): JsonRpcResponse {
        val requestedVersion = McpProtocolVersions.requestedVersion(request.params)
        val negotiatedVersion = McpProtocolVersions.negotiate(requestedVersion)
        Log.i(TAG, "MCP protocol negotiated: requested=$requestedVersion selected=$negotiatedVersion")

        return successResponse(request.id, buildJsonObject {
            put("protocolVersion", negotiatedVersion)
            putJsonObject("capabilities") {
                putJsonObject("tools") {
                    put("listChanged", false)
                }
            }
            putJsonObject("serverInfo") {
                put("name", SERVER_NAME)
                put("version", SERVER_VERSION)
            }
            put("instructions", SERVER_INSTRUCTIONS)
        })
    }

    private fun createSession(): String {
        val sessionId = UUID.randomUUID().toString()
        // Evict oldest entries if cap reached to prevent unbounded growth
        if (sessions.size >= MAX_SESSIONS) {
            sessions.entries.minByOrNull { it.value }?.let { sessions.remove(it.key) }
        }
        sessions[sessionId] = System.currentTimeMillis()
        Log.i(TAG, "MCP client initialized. Session: $sessionId")
        return sessionId
    }

    private fun appendCorsHeaders(call: ApplicationCall, origin: String) {
        call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, origin)
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.Origin)
    }

    private fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
        val tools = McpToolRegistry.getAllTools()
        Log.d(TAG, "tools/list: returning ${tools.size} tools")
        return successResponse(request.id, buildJsonObject {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                    }
                }
            }
        })
    }

    private suspend fun handleToolsCall(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params?.jsonObject
            ?: return errorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing params")
        val toolName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return errorResponse(request.id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing tool name")
        val arguments = params["arguments"]?.jsonObject

        Log.d(TAG, "tools/call: $toolName")

        val startMs = System.currentTimeMillis()
        return try {
            val result = toolHandler.handleToolCall(toolName, arguments)
            val latencyMs = (System.currentTimeMillis() - startMs).toInt()
            CommandLog.add(CommandLog.Entry(
                timestamp = startMs,
                command = toolName,
                latencyMs = latencyMs,
                success = !result.isError,
                category = toolCategory(toolName)
            ))
            successResponse(request.id, buildJsonObject {
                putJsonArray("content") {
                    result.content.forEach { block ->
                        addJsonObject {
                            put("type", block.type)
                            block.text?.let { put("text", it) }
                            block.data?.let { put("data", it) }
                            block.mimeType?.let { put("mimeType", it) }
                        }
                    }
                }
                if (result.isError) put("isError", true)
            })
        } catch (e: Exception) {
            val latencyMs = (System.currentTimeMillis() - startMs).toInt()
            CommandLog.add(CommandLog.Entry(
                timestamp = startMs,
                command = toolName,
                latencyMs = latencyMs,
                success = false,
                category = toolCategory(toolName)
            ))
            Log.e(TAG, "Tool execution error: $toolName", e)
            errorResponse(request.id, JsonRpcErrorCodes.INTERNAL_ERROR, "Tool error: ${e.message ?: "unknown"}")
        }
    }

    private fun toolCategory(toolName: String): CommandLog.Category = when {
        toolName in setOf("android_tap", "android_long_press", "android_double_tap",
            "android_swipe", "android_pinch", "android_drag") -> CommandLog.Category.GESTURE
        toolName in setOf("android_get_ui_tree", "android_screenshot", "android_find_elements",
            "android_get_screen_context", "android_get_notifications", "android_screenshot_diff",
            "android_accessibility_audit", "android_get_recent_toasts", "android_get_device_info",
            "android_list_devices") -> CommandLog.Category.OBSERVE
        toolName in setOf("android_wait_for_element", "android_wait_for_gone",
            "android_wait_for_idle", "android_scroll_to_element") -> CommandLog.Category.WAIT
        toolName in setOf("android_input_text", "android_press_key",
            "android_global_action") -> CommandLog.Category.INPUT
        else -> CommandLog.Category.MANAGE
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        server?.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
        server = null
        sessions.clear()
        lastRequestTimestamp.set(0L)
        releaseScreenWakeLock()
        Log.i(TAG, "MCP HTTP server stopped")
    }

    fun releaseScreenWakeLock() {
        if (screenWakeLock?.isHeld == true) {
            screenWakeLock?.release()
            Log.i(TAG, "Screen wake lock released")
        }
        screenWakeLock = null
    }

    fun getPort(): Int = port
    fun isRunning(): Boolean = server != null
    fun getActiveConnectionCount(): Int = sessions.size
    fun getLastRequestTimestamp(): Long = lastRequestTimestamp.get()
    fun isClientActive(windowMs: Long = 30_000L): Boolean {
        val last = lastRequestTimestamp.get()
        return last > 0 && (System.currentTimeMillis() - last) < windowMs
    }
}
