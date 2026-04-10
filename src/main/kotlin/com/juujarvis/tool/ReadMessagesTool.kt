package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.messaging.MessagingService
import com.juujarvis.model.ChannelType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
class ReadMessagesTool(private val messagingService: MessagingService) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    override val name = "read_messages"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Read recent messages from a messaging channel (e.g. iMessage). " +
                "Optionally filter to messages with a specific contact. " +
                "Returns sent and received messages, newest first."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "channel" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("IMESSAGE", "SIGNAL", "SMS", "EMAIL"),
                                    "description" to "The messaging channel to read from"
                                ),
                                "contact" to mapOf(
                                    "type" to "string",
                                    "description" to "Optional: phone number or email to filter messages to/from a specific contact"
                                ),
                                "limit" to mapOf(
                                    "type" to "integer",
                                    "description" to "Max number of messages to return (default 20, max 100)"
                                )
                            )
                        )
                    )
                    .required(JsonValue.from(listOf("channel")))
                    .build()
            )
            .build()
    }

    override fun execute(arguments: Map<String, Any?>): String {
        val channelStr = arguments["channel"] as? String ?: return "Error: channel is required"
        val contact = arguments["contact"] as? String
        val limit = (arguments["limit"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 20

        val channel = try {
            ChannelType.valueOf(channelStr)
        } catch (e: IllegalArgumentException) {
            return "Error: unknown channel '$channelStr'. Available: ${messagingService.availableChannels()}"
        }

        log.info("ReadMessages tool: channel={}, contact={}, limit={}", channel, contact, limit)

        val messages = try {
            messagingService.getMessages(channel, contact, limit)
        } catch (e: IllegalArgumentException) {
            return "Channel $channel is not available. Available channels: ${messagingService.availableChannels()}"
        }

        if (messages.isEmpty()) return "No messages found."

        return messages.joinToString("\n") { msg ->
            val direction = if (msg.isFromMe) "→ ${msg.to}" else "← ${msg.from}"
            "[${formatter.format(msg.timestamp)}] $direction: ${msg.text}"
        }
    }
}
