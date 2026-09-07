package com.juujarvis.controller

import com.juujarvis.model.ChannelType
import com.juujarvis.model.Conversation
import com.juujarvis.model.IncomingMessage
import com.juujarvis.service.MessageRouter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Generic inbound message endpoint for external bridges (iMessage, Signal, email, etc.).
 * Each bridge POSTs a standardized payload; Juujarvis handles the rest.
 */
@RestController
@RequestMapping("/api/inbound")
class InboundMessageController(
    private val messageRouter: MessageRouter,
    @Value("\${juujarvis.imessage.own-handles:}")
    private val ownHandles: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val ownHandleSet: Set<String> by lazy {
        ownHandles.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    }

    @PostMapping
    fun receiveMessage(@RequestBody request: InboundMessageRequest): ResponseEntity<Map<String, String>> {
        if (request.sender.lowercase() in ownHandleSet) {
            log.debug("Ignoring own message from '{}'", request.sender)
            return ResponseEntity.ok(mapOf("status" to "ignored"))
        }

        log.info("Inbound [{}] from '{}': {}", request.channel, request.sender, request.text.take(80))

        val conversation = request.conversation?.let {
            Conversation(
                chatId = it.chatId,
                chatGuid = it.chatGuid ?: it.chatId,
                isGroup = it.isGroup,
                displayName = it.displayName,
                participants = it.participants
            )
        }

        // Combine message text with audio transcriptions and image attachments
        val fullText = buildString {
            append(request.text)
            request.attachments
                ?.filter { it.type == AttachmentType.AUDIO && !it.transcription.isNullOrBlank() }
                ?.forEach { attachment ->
                    if (isNotBlank()) append("\n\n")
                    append("[Audio message transcription]: ${attachment.transcription}")
                }
            request.attachments
                ?.filter { it.type == AttachmentType.IMAGE && !it.url.isNullOrBlank() }
                ?.forEach { attachment ->
                    val encoded = encodeImageAttachment(attachment)
                    if (encoded != null) {
                        if (isNotBlank()) append("\n\n")
                        append("[IMAGE_BASE64:image/jpeg:${encoded.base64}]")
                        append("\n[Attached image: ${encoded.filename}, source: ${encoded.sourcePath}]")
                    }
                }
        }

        val incoming = IncomingMessage(
            userId = request.sender,
            channel = request.channel,
            text = fullText,
            timestamp = request.timestamp?.let { Instant.parse(it) } ?: Instant.now(),
            conversation = conversation
        )

        messageRouter.handleIncoming(incoming)
        return ResponseEntity.ok(mapOf("status" to "processing"))
    }

    private data class EncodedImage(val base64: String, val filename: String, val sourcePath: String)

    private fun encodeImageAttachment(attachment: InboundAttachment): EncodedImage? {
        val filePath = attachment.url ?: return null
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            log.warn("Image attachment not accessible: {}", filePath)
            return null
        }
        return try {
            val img = ImageIO.read(file) ?: return null

            val maxDim = 1600
            val scale = if (maxOf(img.width, img.height) > maxDim) {
                maxDim.toDouble() / maxOf(img.width, img.height)
            } else 1.0

            val resized = if (scale < 1.0) {
                val w = (img.width * scale).toInt()
                val h = (img.height * scale).toInt()
                val buf = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val g = buf.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.drawImage(img, 0, 0, w, h, null)
                g.dispose()
                buf
            } else img

            val out = ByteArrayOutputStream()
            ImageIO.write(resized, "jpg", out)
            val base64 = Base64.getEncoder().encodeToString(out.toByteArray())

            log.info("Processed image attachment: {} ({}x{} → {}KB base64)",
                file.name, img.width, img.height, base64.length / 1024)

            EncodedImage(base64, attachment.filename ?: file.name, file.absolutePath)
        } catch (e: Exception) {
            log.error("Failed to process image attachment: {}", filePath, e)
            null
        }
    }
}

data class InboundMessageRequest(
    val channel: ChannelType,
    val sender: String,
    val text: String,
    val timestamp: String? = null,
    val conversation: InboundConversation? = null,
    val attachments: List<InboundAttachment>? = null
)

data class InboundConversation(
    val chatId: String,
    val chatGuid: String? = null,
    val isGroup: Boolean = false,
    val displayName: String? = null,
    val participants: List<String> = emptyList()
)

data class InboundAttachment(
    val type: AttachmentType,
    val mimeType: String? = null,
    val transcription: String? = null,
    val filename: String? = null,
    val url: String? = null
)

enum class AttachmentType {
    AUDIO,
    IMAGE,
    VIDEO,
    FILE
}
