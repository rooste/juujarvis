package com.juujarvis.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.util.Base64

@Service
class EmailAttachmentService(
    private val authService: MicrosoftAuthService
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()
    private val restClient = RestClient.builder()
        .baseUrl("https://graph.microsoft.com/v1.0")
        .build()

    data class Attachment(
        val name: String,
        val contentType: String,
        val contentBytes: ByteArray,
        val isImage: Boolean,
        val isPdf: Boolean
    )

    /**
     * Fetch attachments for an email message.
     */
    fun getAttachments(messageId: String): List<Attachment> {
        val token = authService.getAccessToken() ?: return emptyList()

        return try {
            val response = restClient.get()
                .uri("/me/messages/$messageId/attachments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)
            val items = json.get("value") ?: return emptyList()

            items.mapNotNull { att ->
                val name = att.get("name")?.asText() ?: return@mapNotNull null
                val contentType = att.get("contentType")?.asText() ?: ""
                val contentBase64 = att.get("contentBytes")?.asText() ?: return@mapNotNull null
                val bytes = Base64.getDecoder().decode(contentBase64)

                val isImage = contentType.startsWith("image/")
                val isPdf = contentType == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)

                if (!isImage && !isPdf) {
                    log.debug("Skipping non-image/pdf attachment: {} ({})", name, contentType)
                    return@mapNotNull null
                }

                Attachment(name, contentType, bytes, isImage, isPdf)
            }
        } catch (e: Exception) {
            log.error("Failed to fetch attachments for message {}: {}", messageId, e.message)
            emptyList()
        }
    }

    /**
     * Extract text from a PDF attachment.
     */
    fun extractPdfText(attachment: Attachment): String {
        return try {
            val doc = Loader.loadPDF(attachment.contentBytes)
            doc.use {
                PDFTextStripper().getText(it).trim()
            }
        } catch (e: Exception) {
            log.error("Failed to extract text from PDF '{}': {}", attachment.name, e.message)
            ""
        }
    }

    /**
     * Get image as base64 for sending to Claude.
     */
    fun imageToBase64(attachment: Attachment): String {
        return Base64.getEncoder().encodeToString(attachment.contentBytes)
    }
}
