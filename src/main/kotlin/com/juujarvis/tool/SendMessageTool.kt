package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SendMessageTool : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "send_message"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Send a message to a family member. Use this when someone asks to notify, " +
                "remind, or contact another family member. The message will be delivered " +
                "through their preferred channel (Signal, iMessage, email, etc.)."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "recipient" to mapOf(
                                    "type" to "string",
                                    "description" to "Name or role of the recipient (e.g., 'mom', 'kids', 'everyone')"
                                ),
                                "message" to mapOf(
                                    "type" to "string",
                                    "description" to "The message to send"
                                )
                            )
                        )
                    )
                    .required(JsonValue.from(listOf("recipient", "message")))
                    .build()
            )
            .build()
    }

    override fun execute(arguments: Map<String, Any?>): String {
        val recipient = arguments["recipient"] as? String ?: return "Error: recipient is required"
        val message = arguments["message"] as? String ?: return "Error: message is required"

        log.info("SendMessage tool executing: to={}, message={}", recipient, message)

        // TODO: Replace with actual message routing through Signal/iMessage/email
        return "[STUB] Message sent to $recipient: \"$message\""
    }
}
