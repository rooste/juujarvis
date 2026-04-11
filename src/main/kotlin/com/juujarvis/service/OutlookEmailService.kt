package com.juujarvis.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class OutlookEmailService(
    private val authService: MicrosoftAuthService
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()
    private val restClient = RestClient.builder()
        .baseUrl("https://graph.microsoft.com/v1.0")
        .build()

    fun isAvailable(): Boolean = authService.isConfigured() && authService.hasTokens()

    fun readRecentEmails(count: Int = 10, folder: String = "inbox"): List<EmailSummary> {
        val token = authService.getAccessToken() ?: return emptyList()

        return try {
            val response = restClient.get()
                .uri("/me/mailFolders/$folder/messages?\$top=$count&\$orderby=receivedDateTime desc&\$select=subject,from,receivedDateTime,bodyPreview,isRead")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)
            val messages = json.get("value") ?: return emptyList()

            messages.map { msg ->
                EmailSummary(
                    id = msg.get("id")?.asText() ?: "",
                    subject = msg.get("subject")?.asText() ?: "(no subject)",
                    from = msg.get("from")?.get("emailAddress")?.get("address")?.asText() ?: "unknown",
                    fromName = msg.get("from")?.get("emailAddress")?.get("name")?.asText(),
                    receivedAt = msg.get("receivedDateTime")?.asText() ?: "",
                    preview = msg.get("bodyPreview")?.asText() ?: "",
                    isRead = msg.get("isRead")?.asBoolean() ?: false
                )
            }
        } catch (e: Exception) {
            log.error("Failed to read emails: {}", e.message)
            emptyList()
        }
    }

    fun readEmail(messageId: String): EmailDetail? {
        val token = authService.getAccessToken() ?: return null

        return try {
            val response = restClient.get()
                .uri("/me/messages/$messageId?\$select=subject,from,toRecipients,receivedDateTime,body")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(String::class.java)

            val msg = objectMapper.readTree(response)
            EmailDetail(
                id = msg.get("id")?.asText() ?: "",
                subject = msg.get("subject")?.asText() ?: "(no subject)",
                from = msg.get("from")?.get("emailAddress")?.get("address")?.asText() ?: "unknown",
                body = msg.get("body")?.get("content")?.asText() ?: "",
                receivedAt = msg.get("receivedDateTime")?.asText() ?: ""
            )
        } catch (e: Exception) {
            log.error("Failed to read email {}: {}", messageId, e.message)
            null
        }
    }

    fun sendEmail(to: String, subject: String, body: String): Boolean {
        val token = authService.getAccessToken() ?: return false

        val payload = objectMapper.createObjectNode().apply {
            putObject("message").apply {
                put("subject", subject)
                putObject("body").apply {
                    put("contentType", "Text")
                    put("content", body)
                }
                putArray("toRecipients").addObject().apply {
                    putObject("emailAddress").apply {
                        put("address", to)
                    }
                }
            }
        }

        return try {
            restClient.post()
                .uri("/me/sendMail")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .retrieve()
                .toBodilessEntity()

            log.info("Email sent to {} with subject '{}'", to, subject)
            true
        } catch (e: Exception) {
            log.error("Failed to send email to {}: {}", to, e.message)
            false
        }
    }

    data class EmailSummary(
        val id: String,
        val subject: String,
        val from: String,
        val fromName: String?,
        val receivedAt: String,
        val preview: String,
        val isRead: Boolean
    )

    data class EmailDetail(
        val id: String,
        val subject: String,
        val from: String,
        val body: String,
        val receivedAt: String
    )
}
