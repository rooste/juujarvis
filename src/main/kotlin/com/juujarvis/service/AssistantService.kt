package com.juujarvis.service

import com.juujarvis.model.ChannelType
import com.juujarvis.model.IncomingMessage
import com.juujarvis.model.OutgoingMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Stub assistant that echoes back for now.
 * Will be replaced with Claude API integration.
 */
@Service
class AssistantService {

    private val log = LoggerFactory.getLogger(javaClass)

    fun process(message: IncomingMessage): OutgoingMessage {
        log.info("Processing message from '{}': {}", message.userId, message.text)

        // TODO: Replace with Claude API call
        val responseText = "Juujarvis heard you say: \"${message.text}\". " +
            "(This is a stub response — Claude integration coming soon!)"

        return OutgoingMessage(
            userId = message.userId,
            channel = message.channel,
            text = responseText
        )
    }
}
