package com.juujarvis.service

import com.juujarvis.model.IncomingMessage
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
class MessageRouter(
    private val assistantService: AssistantService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Handle an incoming message asynchronously.
     * The response streams back via WebSocket.
     */
    @Async
    fun handleIncoming(message: IncomingMessage) {
        log.info("Incoming [{}] from user '{}': {}", message.channel, message.userId, message.text)
        assistantService.processStreaming(message)
    }
}
