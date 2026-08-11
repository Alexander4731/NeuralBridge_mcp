package com.neuralbridge.companion.mcp

import java.net.URI
import java.security.MessageDigest

object McpRequestSecurity {
    const val AUTHORIZATION_HEADER = "Authorization"
    const val BEARER_SCHEME = "Bearer"

    fun extractBearerToken(headerValue: String?): String? {
        val value = headerValue?.trim().orEmpty()
        val separator = value.indexOf(' ')
        if (separator <= 0) return null

        val scheme = value.substring(0, separator)
        val token = value.substring(separator + 1).trim()
        return token.takeIf { scheme.equals(BEARER_SCHEME, ignoreCase = true) && it.isNotEmpty() }
    }

    fun isAuthorized(headerValue: String?, expectedToken: String): Boolean {
        val providedToken = extractBearerToken(headerValue) ?: return false
        return MessageDigest.isEqual(
            providedToken.toByteArray(Charsets.UTF_8),
            expectedToken.toByteArray(Charsets.UTF_8)
        )
    }

    /** Native MCP clients normally omit Origin. Every Origin that is present must be loopback. */
    fun isAllowedOrigin(origin: String?): Boolean {
        if (origin == null) return true

        val uri = try {
            URI(origin)
        } catch (_: Exception) {
            return false
        }

        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return false
        val isLoopbackHost = host.equals("localhost", ignoreCase = true) ||
            host == "127.0.0.1" || host == "::1"

        return !uri.isOpaque &&
            uri.scheme?.lowercase() in setOf("http", "https") &&
            isLoopbackHost &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    }
}
