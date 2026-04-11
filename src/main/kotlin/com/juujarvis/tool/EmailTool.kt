package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.service.ConversationStore
import com.juujarvis.service.EmailAttachmentService
import com.juujarvis.service.OutlookEmailService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class EmailTool(
    private val emailService: OutlookEmailService,
    private val conversationStore: ConversationStore,
    private val emailAttachmentService: EmailAttachmentService
) : JuujarvisTool {

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
                                    "enum" to listOf("read_inbox", "read_message", "send", "reply_all"),
                                    "description" to "Action: read_inbox (list recent emails), read_message (read full email by ID), send (send to one address), reply_all (reply to sender AND all recipients of an email by message_id)"
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

                // Fetch and save attachments so zoom_image can access them
                val attachments = emailAttachmentService.getAttachments(messageId)
                val attachmentInfo = attachments.mapNotNull { att ->
                    when {
                        att.isImage -> {
                            val result = emailAttachmentService.saveAndPreview(att)
                            if (result != null) {
                                val dims = emailAttachmentService.getImageDimensions(result.first)
                                val dimsText = dims?.let { "${it.first}x${it.second}px" } ?: "unknown size"
                                "Image: ${att.name} (id=${result.first}, $dimsText) — use zoom_image to inspect details\n[IMAGE_BASE64:image/jpeg:${result.second}]"
                            } else null
                        }
                        att.isPdf -> {
                            val text = emailAttachmentService.extractPdfText(att)
                            if (text.isNotBlank()) "PDF: ${att.name}\n${text.take(3000)}" else null
                        }
                        else -> null
                    }
                }
                val attachmentText = if (attachmentInfo.isNotEmpty()) {
                    "\n\n--- Attachments ---\n${attachmentInfo.joinToString("\n\n")}"
                } else ""

                val cleanBody = email.body
                    .replace(Regex("src=\"data:image/[^\"]+\"", RegexOption.IGNORE_CASE), "src=\"[inline-image]\"")
                    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&nbsp;", " ").replace("&amp;", "&")
                    .replace("&lt;", "<").replace("&gt;", ">")
                    .trim().take(3000)

                "From: ${email.from}\nSubject: ${email.subject}\nReceived: ${email.receivedAt}\n\n$cleanBody$attachmentText"
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

            "reply_all" -> {
                val messageId = arguments["message_id"] as? String
                    ?: return "Error: message_id is required for reply_all"
                val body = arguments["body"] as? String
                    ?: return "Error: body is required for reply_all"

                // Fetch the original email to get sender and recipients
                val original = emailService.readEmail(messageId)
                    ?: return "Error: could not read original email with ID: $messageId"

                val subject = if (original.subject.startsWith("Re:", ignoreCase = true)) original.subject else "Re: ${original.subject}"

                // Collect all addresses to reply to: sender + other recipients, excluding ourselves
                val allRecipients = mutableSetOf<String>()
                allRecipients.add(original.from)
                allRecipients.addAll(original.toRecipients)
                allRecipients.removeAll { it.equals("juujarvis@outlook.com", ignoreCase = true) }

                if (allRecipients.isEmpty()) return "Error: no recipients to reply to"

                log.info("Reply-all to {} recipients: {}", allRecipients.size, allRecipients)
                val results = allRecipients.map { addr ->
                    val sent = emailService.sendEmail(addr, subject, body)
                    "$addr: ${if (sent) "sent" else "FAILED"}"
                }
                "Reply-all with subject '$subject':\n${results.joinToString("\n")}"
            }

            else -> "Unknown action: $action. Use read_inbox, read_message, send, or reply_all."
        }
    }
}
