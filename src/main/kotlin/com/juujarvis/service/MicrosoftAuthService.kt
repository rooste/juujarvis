package com.juujarvis.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.io.File

@Service
class MicrosoftAuthService(
    @Value("\${juujarvis.microsoft.client-id:}")
    private val clientId: String,
    @Value("\${juujarvis.microsoft.client-secret:}")
    private val clientSecret: String,
    @Value("\${juujarvis.microsoft.tenant-id:}")
    private val tenantId: String
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()
    private val tokenFile = File("secrets/microsoft-tokens.json")
    private val restClient = RestClient.create()

    private val scopes = "Mail.Read Mail.Send offline_access User.Read"
    private val redirectUri = "http://localhost:8080/auth/microsoft/callback"

    fun isConfigured(): Boolean = clientId.isNotBlank() && clientSecret.isNotBlank()

    fun buildAuthorizationUrl(): String {
        val base = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
        return "$base?client_id=$clientId&response_type=code&redirect_uri=$redirectUri&scope=$scopes&response_mode=query"
    }

    fun exchangeCodeForTokens(code: String): Boolean {
        val tokenUrl = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"

        val form = LinkedMultiValueMap<String, String>()
        form.add("client_id", clientId)
        form.add("client_secret", clientSecret)
        form.add("code", code)
        form.add("redirect_uri", redirectUri)
        form.add("grant_type", "authorization_code")
        form.add("scope", scopes)

        return try {
            val response = restClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)
            saveTokens(json)
            log.info("Microsoft OAuth tokens saved successfully")
            true
        } catch (e: Exception) {
            log.error("Failed to exchange Microsoft auth code: {}", e.message)
            false
        }
    }

    fun getAccessToken(): String? {
        if (!tokenFile.exists()) return null

        val tokens = objectMapper.readTree(tokenFile)
        val expiresAt = tokens.get("expires_at")?.asLong() ?: 0

        return if (System.currentTimeMillis() < expiresAt - 60_000) {
            tokens.get("access_token")?.asText()
        } else {
            refreshTokens()
        }
    }

    fun hasTokens(): Boolean = tokenFile.exists()

    private fun refreshTokens(): String? {
        if (!tokenFile.exists()) return null

        val tokens = objectMapper.readTree(tokenFile)
        val refreshToken = tokens.get("refresh_token")?.asText() ?: return null

        val tokenUrl = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"

        val form = LinkedMultiValueMap<String, String>()
        form.add("client_id", clientId)
        form.add("client_secret", clientSecret)
        form.add("refresh_token", refreshToken)
        form.add("grant_type", "refresh_token")
        form.add("scope", scopes)

        return try {
            val response = restClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)
            saveTokens(json)
            log.info("Microsoft OAuth tokens refreshed")
            json.get("access_token")?.asText()
        } catch (e: Exception) {
            log.error("Failed to refresh Microsoft tokens: {}", e.message)
            null
        }
    }

    private fun saveTokens(json: JsonNode) {
        tokenFile.parentFile.mkdirs()
        val expiresIn = json.get("expires_in")?.asLong() ?: 3600
        val data = objectMapper.createObjectNode().apply {
            put("access_token", json.get("access_token")?.asText())
            put("refresh_token", json.get("refresh_token")?.asText())
            put("expires_in", expiresIn)
            put("expires_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        tokenFile.writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data))
    }
}
