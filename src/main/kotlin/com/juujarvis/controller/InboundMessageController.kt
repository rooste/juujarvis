package com.juujarvis.controller

import com.juujarvis.model.ChannelType
import com.juujarvis.model.Conversation
import com.juujarvis.model.IncomingMessage
import com.juujarvis.service.MessageRouter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

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

        // Combine message text with any audio transcriptions
        val fullText = buildString {
            append(request.text)
            request.attachments
                ?.filter { it.type == AttachmentType.AUDIO && !it.transcription.isNullOrBlank() }
                ?.forEach { attachment ->
                    if (isNotBlank()) append("\n\n")
                    append("[Audio message transcription]: ${attachment.transcription}")
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
