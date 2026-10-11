package com.juujarvis.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Published when Juujarvis can no longer get Microsoft tokens; the owner gets an iMessage with the fix. */
data class MicrosoftAuthProblem(val reason: String, val secretProblem: Boolean)

/**
 * Microsoft sign-in for the Outlook mailbox (delegated access to the account's own mail).
 *
 * With no client secret configured this is a public client: authorization code + PKCE and nothing that
 * expires except the sign-in itself (refresh tokens roll over on every use; 90 days of inactivity ends
 * them). With a secret it works as before (confidential client), still using PKCE.
 */
@Service
class MicrosoftAuthService(
    @Value("\${juujarvis.microsoft.client-id:}")
    private val clientId: String,
    @Value("\${juujarvis.microsoft.client-secret:}")
    private val clientSecret: String,
    @Value("\${juujarvis.microsoft.tenant-id:}")
    private val tenantId: String,
    private val events: ApplicationEventPublisher
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()
    private val tokenFile = File("secrets/microsoft-tokens.json")
    private val restClient = RestClient.create()

    private val scopes = "Mail.Read Mail.Send Files.ReadWrite offline_access User.Read"
    private val redirectUri = "http://localhost:8080/auth/microsoft/callback"

    private val random = SecureRandom()
    @Volatile private var pendingVerifier: String? = null
    @Volatile private var pendingState: String? = null

    fun isConfigured(): Boolean = clientId.isNotBlank()

    val isPublicClient: Boolean get() = clientSecret.isBlank()

    fun buildAuthorizationUrl(): String {
        val verifier = randomToken(48)
        val state = randomToken(24)
        pendingVerifier = verifier
        pendingState = state
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val base = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
        return "$base?client_id=${enc(clientId)}&response_type=code&redirect_uri=${enc(redirectUri)}" +
            "&scope=${enc(scopes)}&response_mode=query&state=$state&code_challenge=$challenge&code_challenge_method=S256"
    }

    /** Checks that a callback belongs to the sign-in we started (CSRF protection). */
    fun isExpectedState(state: String?): Boolean = state != null && state == pendingState

    fun exchangeCodeForTokens(code: String): Boolean {
        val tokenUrl = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"

        val form = LinkedMultiValueMap<String, String>()
        form.add("client_id", clientId)
        if (!isPublicClient) form.add("client_secret", clientSecret)
        pendingVerifier?.let { form.add("code_verifier", it) }
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
            pendingVerifier = null
            pendingState = null
            log.info("Microsoft OAuth tokens saved successfully ({} client)", if (isPublicClient) "public" else "confidential")
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
        if (!isPublicClient) form.add("client_secret", clientSecret)
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
            val message = e.message.orEmpty()
            // invalid_client: the app credential (client secret) is expired or wrong;
            // invalid_grant: the sign-in itself expired or was revoked
            events.publishEvent(MicrosoftAuthProblem(
                reason = Regex("AADSTS\\d+").find(message)?.value ?: message.take(120),
                secretProblem = message.contains("invalid_client")
            ))
            null
        }
    }

    private fun randomToken(bytes: Int) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { random.nextBytes(it) })

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

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
