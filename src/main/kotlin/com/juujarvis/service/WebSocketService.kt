package com.juujarvis.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.juujarvis.model.OutgoingMessage
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service

@Service
class WebSocketService(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun sendToUser(userId: String, message: OutgoingMessage) {
        log.info("Sending WebSocket message to user '{}'", userId)
        messagingTemplate.convertAndSend("/topic/messages/$userId", message)
    }
}
