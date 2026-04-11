package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.service.OutlookEmailService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class EmailTool(private val emailService: OutlookEmailService) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "email"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Read and send emails via the family Outlook account (juujarvis@outlook.com). " +
                "Use this to check for new emails, read specific messages, or send emails on behalf of the family."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "action" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("read_inbox", "read_message", "send"),
                                    "description" to "Action: read_inbox (list recent emails), read_message (read full email by ID), send (send an email)"
                                ),
                                "count" to mapOf(
                                    "type" to "integer",
                                    "description" to "Number of emails to fetch for read_inbox (default 10, max 25)"
                                ),
                                "message_id" to mapOf(
                                    "type" to "string",
                                    "description" to "Email message ID (required for read_message)"
                                ),
                                "to" to mapOf(
                                    "type" to "string",
                                    "description" to "Recipient email address (required for send)"
                                ),
                                "subject" to mapOf(
                                    "type" to "string",
                                    "description" to "Email subject (required for send)"
                                ),
                                "body" to mapOf(
                                    "type" to "string",
                                    "description" to "Email body text (required for send)"
                                )
                            )
                        )
                    )
                    .required(JsonValue.from(listOf("action")))
                    .build()
            )
            .build()
    }

    override fun execute(arguments: Map<String, Any?>): String {
        if (!emailService.isAvailable()) {
            return "Email is not configured. Visit http://localhost:8080/auth/microsoft/login to connect the Outlook account."
        }

        val action = arguments["action"] as? String ?: return "Error: action is required"

        return when (action) {
            "read_inbox" -> {
                val count = (arguments["count"] as? Number)?.toInt()?.coerceIn(1, 25) ?: 10
                log.info("Reading {} recent emails", count)
                val emails = emailService.readRecentEmails(count)
                if (emails.isEmpty()) return "No emails found."

                emails.joinToString("\n\n") { e ->
                    val read = if (e.isRead) "" else " [UNREAD]"
                    val from = e.fromName?.let { "$it <${e.from}>" } ?: e.from
                    "ID: ${e.id}\nFrom: $from\nSubject: ${e.subject}$read\nReceived: ${e.receivedAt}\nPreview: ${e.preview}"
                }
            }

            "read_message" -> {
                val messageId = arguments["message_id"] as? String
                    ?: return "Error: message_id is required"
                log.info("Reading email {}", messageId)
                val email = emailService.readEmail(messageId)
                    ?: return "Could not read email with ID: $messageId"
                "From: ${email.from}\nSubject: ${email.subject}\nReceived: ${email.receivedAt}\n\n${email.body}"
            }

            "send" -> {
                val to = arguments["to"] as? String ?: return "Error: 'to' is required"
                val subject = arguments["subject"] as? String ?: return "Error: 'subject' is required"
                val body = arguments["body"] as? String ?: return "Error: 'body' is required"
                log.info("Sending email to {} with subject '{}'", to, subject)
                val success = emailService.sendEmail(to, subject, body)
                if (success) "Email sent to $to with subject '$subject'"
                else "Failed to send email to $to"
            }

            else -> "Unknown action: $action. Use read_inbox, read_message, or send."
        }
    }
}
