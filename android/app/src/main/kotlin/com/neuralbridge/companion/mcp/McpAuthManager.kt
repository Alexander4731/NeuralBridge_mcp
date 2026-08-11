package com.neuralbridge.companion.mcp

import android.content.Context
import java.security.SecureRandom

class McpAuthManager(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "neuralbridge_prefs"
        private const val KEY_API_KEY = "nb_mcp_api_key"
        private const val TOKEN_BYTES = 32
        private val HEX = "0123456789abcdef".toCharArray()
        const val HEADER_NAME = McpRequestSecurity.AUTHORIZATION_HEADER
    }

    // Cached in memory after first load to avoid SharedPreferences I/O on every request
    @Volatile
    private var cachedKey: String? = null

    fun getOrCreateApiKey(): String {
        cachedKey?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_API_KEY, null) ?: generateToken().also {
            prefs.edit().putString(KEY_API_KEY, it).apply()
        }
        cachedKey = key
        return key
    }

    fun validateAuthorizationHeader(headerValue: String?): Boolean =
        McpRequestSecurity.isAuthorized(headerValue, getOrCreateApiKey())

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        return CharArray(bytes.size * 2).also { chars ->
            bytes.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                chars[index * 2] = HEX[value ushr 4]
                chars[index * 2 + 1] = HEX[value and 0x0f]
            }
        }.concatToString()
    }
}
