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
import java.io.File
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

    private val tempDir = File("temp-images").also { it.mkdirs() }

    // With native image blocks, Claude handles images efficiently (~1500 tokens per image)
    // so we can afford much larger previews. 5MB is Claude's max per image.
    @org.springframework.beans.factory.annotation.Value("\${juujarvis.image.max-preview-base64-bytes:4000000}")
    private var maxPreviewBase64Bytes: Int = 4_000_000

    /**
     * Save full-size image to temp-images/ and return a reduced preview as base64.
     * Dynamically finds the largest size that fits within the token budget.
     * Returns Pair(imageId, base64Preview).
     */
    fun saveAndPreview(attachment: Attachment): Pair<String, String>? {
        return try {
            val imageId = "img_${System.currentTimeMillis()}_${attachment.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
            val fullFile = File(tempDir, imageId)
            fullFile.writeBytes(attachment.contentBytes)
            log.info("Saved full image: {} ({}KB)", fullFile.path, attachment.contentBytes.size / 1024)

            val img = ImageIO.read(ByteArrayInputStream(attachment.contentBytes)) ?: return null

            // Try original size first, then progressively reduce until under budget
            val previewBytes = fitToBudget(img, maxPreviewBase64Bytes)
            val base64 = Base64.getEncoder().encodeToString(previewBytes)

            log.info("Created preview for {}: {}x{} original → {}KB base64", imageId, img.width, img.height, base64.length / 1024)
            imageId to base64
        } catch (e: Exception) {
            log.error("Failed to save/preview image '{}': {}", attachment.name, e.message)
            null
        }
    }

    /**
     * Crop and return a section of a stored image at requested resolution.
     */
    fun cropImage(imageId: String, x: Int, y: Int, width: Int, height: Int, outputWidth: Int): String? {
        val file = File(tempDir, imageId)
        if (!file.exists()) {
            log.warn("Image not found: {}", imageId)
            return null
        }

        return try {
            val img = ImageIO.read(file) ?: return null

            // Clamp crop region to image bounds
            val cx = x.coerceIn(0, img.width - 1)
            val cy = y.coerceIn(0, img.height - 1)
            val cw = width.coerceIn(1, img.width - cx)
            val ch = height.coerceIn(1, img.height - cy)

            val cropped = img.getSubimage(cx, cy, cw, ch)
            val scale = (outputWidth.toDouble() / cw).coerceAtMost(1.0)
            val outW = (cw * scale).toInt()
            val outH = (ch * scale).toInt()

            val scaled = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
            val g = scaled.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.drawImage(cropped, 0, 0, outW, outH, null)
            g.dispose()

            val out = ByteArrayOutputStream()
            ImageIO.write(scaled, "jpg", out)
            log.info("Cropped image {}: ({}x{} at {},{}) → {}x{} ({}KB)", imageId, cw, ch, cx, cy, outW, outH, out.size() / 1024)
            Base64.getEncoder().encodeToString(out.toByteArray())
        } catch (e: Exception) {
            log.error("Failed to crop image {}: {}", imageId, e.message)
            null
        }
    }

    /**
     * Get full image dimensions for a stored image.
     */
    fun getImageDimensions(imageId: String): Pair<Int, Int>? {
        val file = File(tempDir, imageId)
        if (!file.exists()) return null
        return try {
            val img = ImageIO.read(file) ?: return null
            img.width to img.height
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Progressively reduce image size until the JPEG bytes produce base64
     * within the budget. Starts at full size, reduces by ~30% each step.
     * Returns the largest JPEG that fits.
     */
    private fun fitToBudget(img: BufferedImage, maxBase64Bytes: Int): ByteArray {
        // base64 is ~4/3 of raw bytes, so target raw bytes = budget * 3/4
        val maxRawBytes = maxBase64Bytes * 3 / 4

        // Try original first
        val original = toJpeg(img)
        if (original.size <= maxRawBytes) {
            log.debug("Image fits at original size {}x{} ({}KB)", img.width, img.height, original.size / 1024)
            return original
        }

        // Binary search for the right scale factor
        var lo = 0.1
        var hi = 1.0
        var bestBytes = original

        repeat(8) {
            val mid = (lo + hi) / 2
            val w = (img.width * mid).toInt().coerceAtLeast(1)
            val h = (img.height * mid).toInt().coerceAtLeast(1)
            val bytes = toJpeg(scaleImage(img, w, h))

            if (bytes.size <= maxRawBytes) {
                bestBytes = bytes
                lo = mid // try larger
            } else {
                hi = mid // try smaller
            }
        }

        val finalScale = (lo + hi) / 2
        val finalW = (img.width * finalScale).toInt()
        val finalH = (img.height * finalScale).toInt()
        log.info("Resized image from {}x{} to {}x{} ({}KB → {}KB) to fit {}KB budget",
            img.width, img.height, finalW, finalH,
            original.size / 1024, bestBytes.size / 1024, maxBase64Bytes / 1024)
        return bestBytes
    }

    private fun scaleImage(img: BufferedImage, w: Int, h: Int): BufferedImage {
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()
        return scaled
    }

    private fun toJpeg(img: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }
}
