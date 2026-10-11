package com.juujarvis.service

import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MicrosoftAuthServiceTest {
    private fun service(secret: String = "") = MicrosoftAuthService("client-123", secret, "", { _ -> })

    private fun params(url: String) = URI(url).rawQuery.split("&").associate {
        val (k, v) = it.split("=", limit = 2); k to URLDecoder.decode(v, Charsets.UTF_8)
    }

    @Test
    fun `authorization url uses PKCE and a fresh state`() {
        val s = service()
        assertTrue(s.isPublicClient)
        assertTrue(s.isConfigured())
        val first = params(s.buildAuthorizationUrl())
        assertEquals("S256", first["code_challenge_method"])
        assertEquals(43, first["code_challenge"]!!.length)          // base64url SHA-256
        assertEquals("http://localhost:8080/auth/microsoft/callback", first["redirect_uri"])
        assertTrue(first["scope"]!!.contains("offline_access"))
        val second = params(s.buildAuthorizationUrl())
        assertNotEquals(first["state"], second["state"])
        assertNotEquals(first["code_challenge"], second["code_challenge"])
        assertTrue(s.isExpectedState(second["state"]))             // only the latest sign-in is accepted
        assertFalse(s.isExpectedState(first["state"]))
        assertFalse(s.isExpectedState(null))
        assertFalse(service("a-secret").isPublicClient)
    }
}
