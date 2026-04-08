package com.juujarvis.service

import com.juujarvis.model.OutgoingMessage
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service

@Service
class WebSocketService(
    private val messagingTemplate: SimpMessagingTemplate
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun sendToUser(userId: String, message: OutgoingMessage) {
        messagingTemplate.convertAndSend("/topic/messages/$userId", message)
    }

    /** Send a streaming text chunk to the user */
    fun sendStreamChunk(userId: String, text: String) {
        messagingTemplate.convertAndSend(
            "/topic/stream/$userId",
            mapOf("type" to "chunk", "text" to text)
        )
    }

    /** Signal that the stream is complete */
    fun sendStreamEnd(userId: String) {
        messagingTemplate.convertAndSend(
            "/topic/stream/$userId",
            mapOf("type" to "end")
        )
    }

    /** Notify the user about tool execution status */
    fun sendToolStatus(userId: String, toolName: String, status: String) {
        messagingTemplate.convertAndSend(
            "/topic/stream/$userId",
            mapOf("type" to "tool", "tool" to toolName, "status" to status)
        )
    }
}
