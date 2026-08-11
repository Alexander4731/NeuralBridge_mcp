package com.neuralbridge.companion.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRequestSecurityTest {
    private val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `server binds only to IPv4 loopback`() {
        assertEquals("127.0.0.1", McpHttpServer.MCP_HOST)
    }

    @Test
    fun `missing and malformed authorization headers are rejected`() {
        assertNull(McpRequestSecurity.extractBearerToken(null))
        assertNull(McpRequestSecurity.extractBearerToken(""))
        assertNull(McpRequestSecurity.extractBearerToken("Basic abc"))
        assertNull(McpRequestSecurity.extractBearerToken("Bearer"))
        assertNull(McpRequestSecurity.extractBearerToken("Bearer   "))
        assertFalse(McpRequestSecurity.isAuthorized(null, token))
        assertFalse(McpRequestSecurity.isAuthorized("Bearer wrong", token))
    }

    @Test
    fun `valid bearer token is accepted with case insensitive scheme`() {
        assertTrue(McpRequestSecurity.isAuthorized("Bearer $token", token))
        assertTrue(McpRequestSecurity.isAuthorized("bearer $token", token))
    }

    @Test
    fun `native clients without origin are allowed`() {
        assertTrue(McpRequestSecurity.isAllowedOrigin(null))
    }

    @Test
    fun `loopback browser origins are allowed`() {
        assertTrue(McpRequestSecurity.isAllowedOrigin("http://127.0.0.1:3000"))
        assertTrue(McpRequestSecurity.isAllowedOrigin("https://localhost"))
        assertTrue(McpRequestSecurity.isAllowedOrigin("http://[::1]:8080"))
    }

    @Test
    fun `non loopback and malformed origins are rejected`() {
        val rejected = listOf(
            "null",
            "https://attacker.example",
            "http://192.168.1.20:3000",
            "file://localhost/tmp/client.html",
            "http://user@localhost:3000",
            "http://localhost:3000/path",
            "http://localhost:3000?redirect=attacker.example",
            "not a URI"
        )

        rejected.forEach { origin ->
            assertFalse("Origin should be rejected: $origin", McpRequestSecurity.isAllowedOrigin(origin))
        }
    }
}
