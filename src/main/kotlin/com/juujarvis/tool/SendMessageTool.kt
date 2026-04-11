package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.messaging.MessagingService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SendMessageTool(private val messagingService: MessagingService) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "send_message"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Send a message to a family member or a group chat. " +
                "For individual messages, use 'recipient'. " +
                "For group chats, use 'group_members' with the names of the people in the group. " +
                "PREFER sending to a group when the message is relevant to multiple people and a matching group exists."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "recipient" to mapOf(
                                    "type" to "string",
                                    "description" to "Name of an individual recipient (e.g., 'Mom', 'Dad'). Use this for 1-on-1 messages."
                                ),
                                "group_members" to mapOf(
                                    "type" to "array",
                                    "items" to mapOf("type" to "string"),
                                    "description" to "Names of the group members to identify the group chat (e.g., ['Dad', 'Mom']). Use this to send to a group chat."
                                ),
                                "message" to mapOf(
                                    "type" to "string",
                                    "description" to "The message to send"
                                )
                            )
                        )
                    )
                    .required(JsonValue.from(listOf("message")))
                    .build()
            )
            .build()
    }

    @Suppress("UNCHECKED_CAST")
    override fun execute(arguments: Map<String, Any?>): String {
        val message = arguments["message"] as? String ?: return "Error: message is required"
        val groupMembers = arguments["group_members"] as? List<String>
        val recipient = arguments["recipient"] as? String

        if (groupMembers != null && groupMembers.isNotEmpty()) {
            log.info("SendMessage tool: to group with members={}", groupMembers)
            return messagingService.sendToGroup(groupMembers, message)
        }

        if (recipient != null) {
            log.info("SendMessage tool: to={}", recipient)
            return messagingService.sendToUser(recipient, message)
        }

        return "Error: either 'recipient' or 'group_members' is required"
    }
}
