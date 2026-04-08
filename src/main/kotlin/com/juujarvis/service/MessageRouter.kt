package com.juujarvis.service

import com.juujarvis.model.IncomingMessage
import com.juujarvis.model.OutgoingMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MessageRouter(
    private val assistantService: AssistantService,
    private val webSocketService: WebSocketService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun handleIncoming(message: IncomingMessage): OutgoingMessage {
        log.info("Incoming [{}] from user '{}': {}", message.channel, message.userId, message.text)

        val response = assistantService.process(message)

        log.info("Responding to '{}': {}", message.userId, response.text)

        // Route response back through the appropriate channel
        webSocketService.sendToUser(response.userId, response)

        return response
    }
}
