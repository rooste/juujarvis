package com.juujarvis.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

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
     * Get image as base64 for sending to Claude, resizing if too large.
     * Claude has token limits so we cap images at ~1MB.
     */
    fun imageToBase64(attachment: Attachment): String {
        val maxBytes = 1_000_000
        val bytes = if (attachment.contentBytes.size > maxBytes) {
            resizeImage(attachment.contentBytes, attachment.contentType)
        } else {
            attachment.contentBytes
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun resizeImage(bytes: ByteArray, contentType: String): ByteArray {
        return try {
            val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return bytes
            val scale = Math.min(1600.0 / img.width, 1600.0 / img.height).coerceAtMost(1.0)
            val newW = (img.width * scale).toInt()
            val newH = (img.height * scale).toInt()
            val resized = BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB)
            val g = resized.createGraphics()
            g.drawImage(img, 0, 0, newW, newH, null)
            g.dispose()
            val out = ByteArrayOutputStream()
            ImageIO.write(resized, "jpg", out)
            log.info("Resized image from {}x{} to {}x{} ({}KB → {}KB)", img.width, img.height, newW, newH, bytes.size / 1024, out.size() / 1024)
            out.toByteArray()
        } catch (e: Exception) {
            log.warn("Failed to resize image: {}", e.message)
            bytes
        }
    }
}
