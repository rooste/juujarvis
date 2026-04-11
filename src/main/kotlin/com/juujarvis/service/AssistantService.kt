package com.juujarvis.service

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.*
import com.juujarvis.messaging.MessagingService
import com.juujarvis.model.ChannelType
import com.juujarvis.model.IncomingMessage
import com.juujarvis.tool.ToolRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class AssistantService(
    private val anthropicClient: AnthropicClient,
    private val toolRegistry: ToolRegistry,
    private val webSocketService: WebSocketService,
    private val messagingService: MessagingService,
    private val userService: UserService,
    private val conversationStore: ConversationStore,
    private val outlookEmailService: OutlookEmailService,
    @Value("\${juujarvis.anthropic.model:claude-sonnet-4-20250514}")
    private val modelId: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_TOOL_LOOPS = 10

        private const val BASE_PROMPT = """You are Juujarvis, an AI family assistant running on the family Mac Mini on Dad's oak desk in Coppell, Texas.

Your name honors the Juujärvi heritage. Juujärvi is a lake in Kemijärvi, Finnish Lapland, nestled along the Kemijoki River. When the Juujärvi family emigrated to the United States about a hundred years ago, they shortened their name to Jarv. Your name playfully reunites both halves — the Finnish roots and the American branch — while nodding to a certain famous AI butler from the Iron Man movies. You may not have a suit of armor or a holographic workshop, but you've got calendars, reminders, and an unlimited supply of dad jokes — which, frankly, is the more dangerous arsenal.

You help the family stay organized by managing their calendar, sending messages between family members, searching the web, and providing helpful reminders.

You have access to tools for managing the calendar, sending messages, searching the web, and tracking tasks. Use them when the user asks you to create events, check the schedule, remind family members, look something up, or contact someone.

Be warm, helpful, and concise. You're part of the family — think of yourself as a helpful household assistant who knows and cherishes the family's Finnish-American heritage. Keep emoji use to a minimum — skip them in normal conversation. A dad joke punchline can earn one, but that's about it.

When asked to notify or remind family members, use the send_message tool.
Your reply will automatically be delivered to the conversation this message came from. Only use send_message if you need to reach someone in a DIFFERENT conversation.

GROUP CHAT BEHAVIOR:
In group conversations, not every message is meant for you. You should ONLY respond when:
- Someone addresses you by name (Juujarvis, Jarvis, etc.)
- Someone asks a question or makes a request that clearly needs your help (calendar, reminders, lookups, sending messages)
- Someone asks a question to the group that you can helpfully answer (e.g., "what time is dinner?") and it's clear from context they'd welcome your input
Do NOT respond to:
- Casual conversation between family members
- Messages that are clearly directed at another person
- Reactions, acknowledgements, or chit-chat (e.g., "haha", "ok", "love you")
EXCEPTION: If someone sets up a perfect opportunity for a dad joke, you may jump in with one. Keep it short. Don't overdo it — once in a while is charming, every time is annoying.
If you decide a message doesn't need your response, reply with exactly: [NO_RESPONSE]

EMAIL BEHAVIOR:
You receive emails forwarded to juujarvis@outlook.com. Many are routine and require no action.

SCHOOL EMAILS:
Dad (heikki.taavettila@gmail.com) forwards school-related emails from the kids' schools. When you receive these:
1. Analyze the content carefully. Most school emails are informational newsletters, announcements, or routine notices that need NO action.
2. If the email contains actionable items for the family (parent signatures needed, events to attend, fees to pay, forms to submit), reply summarizing ONLY the actionable items.
3. If the email contains assignment due dates or project deadlines for the kids, create calendar events and send reminders to the relevant child (Samuli or Sonja) via iMessage as the date approaches.
4. If an assignment due date has already passed and wasn't completed, remind the child immediately and notify the parents.
5. When reminding kids about upcoming assignments, tell them they need to let you know when it's done. If they don't confirm completion before the due date, escalate to the parents.
6. If the email is from a teacher raising a specific concern about one of the kids (behavior, grades, attendance, etc.), send a message to the parent group chat immediately with a summary of the concern.
7. If the email is purely informational with nothing the family needs to act on, reply with: [NO_RESPONSE]
Do NOT over-react to routine school communications. Focus on what requires parent or student action.

FOREST FINANCE EMAILS:
Dad co-owns forest property in Finland with his brother. When you receive emails with receipt or invoice attachments (images or PDFs) related to the forest:
1. Analyze the attachment to extract: date, description, vendor/payer, whether it's income or expense, and the EUR amount.
2. Use the record_forest_transaction tool to add it to the spreadsheet. The tool will automatically convert EUR to USD at the historical rate.
3. Reply confirming what was recorded.
If you cannot extract the data clearly, ask Dad to clarify rather than guessing."""
    }

    fun processStreaming(message: IncomingMessage) {
        val senderUser = userService.findByHandle(message.userId)
        val senderName = senderUser?.name ?: message.userId
        log.info("Processing message from '{}' ({}): {}", senderName, message.userId, message.text)

        val conversationId = when {
            message.conversation != null -> message.conversation.chatId
            message.channel == ChannelType.EMAIL -> "email-${message.userId}"
            else -> "web-ui-${message.userId}"
        }

        // Save the incoming user message
        conversationStore.saveTurn(conversationId, "user", message.text, senderName, message.channel.name, message.timestamp)

        val systemPrompt = buildSystemPrompt(message, senderName)

        val userText = if (message.channel == ChannelType.IMESSAGE) {
            "[$senderName]: ${message.text}"
        } else {
            message.text
        }

        // Load conversation history and build message list
        val recentTurns = conversationStore.loadRecentTurns(conversationId, limit = 20)
        val conversationMessages = mutableListOf<MessageParam>()

        // Add historical turns (excluding the current message which is the last one)
        val history = sanitizeHistory(recentTurns.dropLast(1))
        history.forEach { turn ->
            val role = if (turn.role == "user") MessageParam.Role.USER else MessageParam.Role.ASSISTANT
            val text = if (turn.role == "user" && message.channel == ChannelType.IMESSAGE) {
                "[${turn.senderName ?: "unknown"}]: ${turn.content}"
            } else {
                turn.content
            }
            conversationMessages.add(
                MessageParam.builder()
                    .role(role)
                    .content(MessageParam.Content.ofString(text))
                    .build()
            )
        }

        // Add the current message
        conversationMessages.add(
            MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(MessageParam.Content.ofString(userText))
                .build()
        )

        var loopCount = 0
        val textBuffer = StringBuilder()

        while (loopCount < MAX_TOOL_LOOPS) {
            loopCount++
            log.debug("Claude call loop {}", loopCount)

            val paramsBuilder = MessageCreateParams.builder()
                .model(Model.of(modelId))
                .maxTokens(2048L)
                .system(systemPrompt)

            toolRegistry.definitions().forEach { tool ->
                paramsBuilder.addTool(tool)
            }

            conversationMessages.forEach { msg ->
                paramsBuilder.addMessage(msg)
            }

            val params = paramsBuilder.build()

            val accumulator = MessageAccumulator.create()
            textBuffer.clear()

            try {
                val streamResponse = anthropicClient.messages().createStreaming(params)
                streamResponse.use { sr ->
                    sr.stream().forEach { event: RawMessageStreamEvent ->
                        accumulator.accumulate(event)
                        event.contentBlockDelta().ifPresent { delta ->
                            delta.delta().text().ifPresent { textDelta ->
                                val chunk: String = textDelta.text()
                                textBuffer.append(chunk)
                                if (message.channel != ChannelType.IMESSAGE) {
                                    webSocketService.sendStreamChunk(message.userId, chunk)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("Error streaming from Claude", e)
                deliverResponse(message, "My brain seems to be on a Finnish holiday right now. Try again in a moment!")
                return
            }

            val accumulatedMessage = accumulator.message()

            val toolUseBlocks = accumulatedMessage.content().filter { block ->
                block.toolUse().isPresent
            }

            if (toolUseBlocks.isEmpty()) {
                log.info("Response complete (no tool use): {} chars", textBuffer.length)
                deliverAndSave(message, conversationId, textBuffer.toString())
                return
            }

            log.info("Claude requested {} tool call(s)", toolUseBlocks.size)

            val assistantContentBlocks = mutableListOf<ContentBlockParam>()
            accumulatedMessage.content().forEach { block ->
                block.text().ifPresent { textBlock ->
                    assistantContentBlocks.add(
                        ContentBlockParam.ofText(
                            TextBlockParam.builder().text(textBlock.text()).build()
                        )
                    )
                }
                block.toolUse().ifPresent { toolUse ->
                    assistantContentBlocks.add(
                        ContentBlockParam.ofToolUse(
                            ToolUseBlockParam.builder()
                                .id(toolUse.id())
                                .name(toolUse.name())
                                .input(toolUse._input())
                                .build()
                        )
                    )
                }
            }
            conversationMessages.add(
                MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .content(MessageParam.Content.ofBlockParams(assistantContentBlocks))
                    .build()
            )

            val toolResults = mutableListOf<ContentBlockParam>()
            toolUseBlocks.forEach { block ->
                val toolUse = block.toolUse().get()
                val toolName = toolUse.name()
                val toolId = toolUse.id()

                val arguments = parseToolArguments(toolUse._input())

                log.info("Executing tool '{}' with args: {}", toolName, arguments)
                webSocketService.sendToolStatus(message.userId, toolName, "executing")

                val result = try {
                    toolRegistry.execute(toolName, arguments)
                } catch (e: Exception) {
                    log.error("Tool '{}' failed", toolName, e)
                    "Error executing tool: ${e.message}"
                }

                log.info("Tool '{}' result: {}", toolName, result)
                webSocketService.sendToolStatus(message.userId, toolName, "completed")

                toolResults.add(
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                            .toolUseId(toolId)
                            .content(ToolResultBlockParam.Content.ofString(result))
                            .build()
                    )
                )
            }

            conversationMessages.add(
                MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(MessageParam.Content.ofBlockParams(toolResults))
                    .build()
            )
        }

        log.warn("Max tool loops ({}) reached", MAX_TOOL_LOOPS)
        deliverAndSave(message, conversationId, textBuffer.toString())
    }

    private fun buildSystemPrompt(message: IncomingMessage, senderName: String): String {
        val familyMembers = userService.getAllUsers().joinToString("\n") { user ->
            val handles = user.contacts
                .filter { it.channelType == ChannelType.IMESSAGE }
                .joinToString(", ") { it.address }
            val localTime = java.time.ZonedDateTime.now(user.timezone)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            "- ${user.name} (${user.type}, ${user.timezone}, local time: $localTime)" + if (handles.isNotBlank()) " — $handles" else ""
        }

        val conversationContext = when {
            message.conversation == null ->
                "This message arrived via ${message.channel}."
            message.conversation.isGroup -> {
                val participantNames = message.conversation.participants.map { handle ->
                    userService.findByHandle(handle)?.name ?: handle
                }
                "This is a GROUP conversation" +
                    (message.conversation.displayName?.let { " named '$it'" } ?: "") +
                    " with participants: ${participantNames.joinToString(", ")}." +
                    " $senderName sent this message."
            }
            else ->
                "This is a 1-on-1 conversation with $senderName."
        }

        val summaries = conversationStore.loadRecentSummaries(days = 7)
        val summaryContext = if (summaries.isNotEmpty()) {
            val lines = summaries.joinToString("\n\n") { s ->
                "=== ${s.summaryDate} ===\n${s.summary}" +
                    (s.followUps?.let { "\nFollow-ups: $it" } ?: "")
            }
            "\n\nRecent daily summaries (for long-term context):\n$lines"
        } else ""

        // Load profiles for participants in this conversation
        val relevantProfiles = buildRelevantProfiles(message)

        val senderUser = userService.findByHandle(message.userId)
        val senderTimezone = senderUser?.timezone ?: java.time.ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(senderTimezone)
        val dateTime = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)"))

        val openTasks = conversationStore.loadTasksByStatus("open")
        val taskContext = if (openTasks.isNotEmpty()) {
            val taskLines = openTasks.joinToString("\n") { t ->
                val assigned = t.assignedTo?.let { " → $it" } ?: ""
                val desc = t.description?.let { " — $it" } ?: ""
                "#${t.id}: ${t.title}$assigned$desc"
            }
            "\n\nOPEN TASKS:\n$taskLines"
        } else ""

        return """$BASE_PROMPT

Current date and time: $dateTime ($senderTimezone)

When creating calendar events, if a date appears to be in the past (e.g., a year that has already passed), assume the user means the next upcoming occurrence and adjust the year accordingly. Always confirm the date with the user if ambiguous.

TIMEZONE AWARENESS:
Each family member has a timezone on their profile. If someone mentions they have moved, are traveling, or are in a different timezone, update their timezone using the manage_user tool with the 'update' action and the 'timezone' parameter.

TASK TRACKING:
You have a task list. When someone asks you to do something that can't be done immediately (e.g., "remind me to follow up", "look into X", "we need to plan Y"), create a task using manage_task. When a conversation relates to an existing open task, mention it and update or complete it as appropriate. Periodically review open tasks and proactively bring up ones that may need attention.$taskContext

Family members:
$familyMembers$relevantProfiles
${buildGroupChatContext()}
${ buildRecentActivityContext(message) }
${ buildRecentEmailContext() }
Current conversation:
$conversationContext$summaryContext"""
    }

    /**
     * Build a summary of recent messages from OTHER conversations,
     * so Juujarvis has cross-conversation awareness (e.g., when instructed
     * from the web UI to act on something that happened in a group chat).
     */
    private fun buildRecentActivityContext(message: IncomingMessage): String {
        val currentConvId = when {
            message.conversation != null -> message.conversation.chatId
            else -> "web-ui-${message.userId}"
        }

        val since = java.time.Instant.now().minusSeconds(3600) // last hour
        val recentTurns = conversationStore.loadTurnsSince(since)

        // Group by conversation, exclude the current one
        val otherConversations = recentTurns
            .filter { it.conversationId != currentConvId }
            .groupBy { it.conversationId }

        if (otherConversations.isEmpty()) return ""

        val lines = otherConversations.map { (convId, turns) ->
            val label = if (convId.startsWith("web-ui-")) "Web UI" else "Chat $convId"
            val messages = turns.takeLast(5).joinToString("\n  ") { t ->
                "[${t.senderName ?: t.role}]: ${t.content.take(200)}"
            }
            "--- $label ---\n  $messages"
        }.joinToString("\n")

        return "\nRecent activity in other conversations (last hour):\n$lines\n"
    }

    private fun buildRecentEmailContext(): String {
        val emails = conversationStore.loadRecentEmailSummaries(hours = 24)
        if (emails.isEmpty()) return ""
        val lines = emails.take(10).joinToString("\n") { e ->
            val from = e.fromName?.let { "$it <${e.fromAddress}>" } ?: e.fromAddress
            "- From: $from | Subject: ${e.subject} | ${e.summary.take(150)}"
        }
        return "\nRecent emails (last 24h):\n$lines\n"
    }

    private fun buildGroupChatContext(): String {
        val groups = discoverGroupChats()
        if (groups.isEmpty()) return ""
        val lines = groups.joinToString("\n") { (name, members) ->
            "- $name: ${members.joinToString(", ")}"
        }
        return "\nKnown group chats (use send_message with group_members to send to these):\n$lines\n"
    }

    /**
     * Discover group chats directly from iMessage's chat.db,
     * resolving handles to family member names.
     */
    private fun discoverGroupChats(): List<Pair<String, List<String>>> {
        val dbPath = System.getProperty("user.home") + "/Library/Messages/chat.db"
        return try {
            java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                val groups = mutableListOf<Pair<String, List<String>>>()
                conn.createStatement().use { s ->
                    val rs = s.executeQuery("SELECT ROWID, display_name FROM chat WHERE style = 43")
                    while (rs.next()) {
                        val chatRowId = rs.getLong("ROWID")
                        val displayName = rs.getString("display_name")
                        val handles = mutableListOf<String>()
                        conn.prepareStatement(
                            "SELECT h.id FROM handle h JOIN chat_handle_join chj ON chj.handle_id = h.ROWID WHERE chj.chat_id = ?"
                        ).use { stmt ->
                            stmt.setLong(1, chatRowId)
                            val hrs = stmt.executeQuery()
                            while (hrs.next()) handles += hrs.getString("id")
                        }
                        val memberNames = handles.map { handle ->
                            userService.findByHandle(handle)?.name ?: handle
                        }
                        val label = displayName?.let { "'$it'" } ?: "unnamed"
                        groups += label to memberNames
                    }
                }
                groups
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildRelevantProfiles(message: IncomingMessage): String {
        val personIds = mutableSetOf<String>()

        // Add the sender
        val senderUser = userService.findByHandle(message.userId)
        if (senderUser != null) personIds.add(senderUser.name.lowercase())

        // Add all participants in the conversation
        message.conversation?.participants?.forEach { handle ->
            val user = userService.findByHandle(handle)
            if (user != null) personIds.add(user.name.lowercase())
        }

        // For web UI, add the user
        if (message.channel != ChannelType.IMESSAGE) {
            personIds.add(message.userId.lowercase())
        }

        if (personIds.isEmpty()) return ""

        val profiles = personIds.mapNotNull { conversationStore.loadPersonProfile(it) }
        if (profiles.isEmpty()) return ""

        val lines = profiles.joinToString("\n\n") { p ->
            "${p.personId}:\n${p.profile}"
        }
        return "\n\nWhat you know about the people in this conversation:\n$lines"
    }

    private fun deliverAndSave(message: IncomingMessage, conversationId: String, text: String) {
        if (text.trim() == "[NO_RESPONSE]") {
            log.info("Claude chose not to respond in conversation {}", conversationId)
            return
        }
        deliverResponse(message, text)
        if (text.isNotBlank()) {
            conversationStore.saveTurn(conversationId, "assistant", text, "Juujarvis", message.channel.name, Instant.now())
        }
    }

    private fun deliverResponse(message: IncomingMessage, text: String) {
        when (message.channel) {
            ChannelType.IMESSAGE -> {
                if (text.isNotBlank()) {
                    val sent = if (message.conversation != null) {
                        messagingService.sendToConversation(message.conversation, text)
                    } else {
                        messagingService.sendDirect(ChannelType.IMESSAGE, message.userId, text)
                    }
                    log.info("iMessage reply to {}: {} (sent={})",
                        message.conversation?.chatId ?: message.userId, text.take(80), sent)
                    webSocketService.broadcastIMessage("out", message.userId, text)
                }
            }
            ChannelType.EMAIL -> {
                if (text.isNotBlank() && outlookEmailService.isAvailable()) {
                    // Extract subject from the original message for the reply
                    val subject = extractEmailSubject(message.text)
                    val replySubject = if (subject.startsWith("Re:", ignoreCase = true)) subject else "Re: $subject"
                    val sent = outlookEmailService.sendEmail(message.userId, replySubject, text)
                    log.info("Email reply to {}: {} (sent={})", message.userId, text.take(80), sent)
                }
            }
            else -> {
                webSocketService.sendStreamChunk(message.userId, text)
                webSocketService.sendStreamEnd(message.userId)
            }
        }
    }

    private fun extractEmailSubject(messageText: String): String {
        val subjectLine = messageText.lines().find { it.startsWith("Subject: ") }
        return subjectLine?.removePrefix("Subject: ") ?: "Juujarvis"
    }

    /**
     * Ensure USER/ASSISTANT messages alternate as required by the Anthropic API.
     * Drop turns that would break alternation; ensure list starts with USER.
     */
    private fun sanitizeHistory(turns: List<ConversationTurn>): List<ConversationTurn> {
        if (turns.isEmpty()) return turns
        val result = mutableListOf<ConversationTurn>()
        for (turn in turns) {
            if (result.isEmpty()) {
                if (turn.role == "user") result.add(turn)
            } else {
                if (turn.role != result.last().role) {
                    result.add(turn)
                }
                // skip consecutive same-role turns (keep the later one would break order, so skip)
            }
        }
        // Must end with user (since we're about to add the current user message)
        // If it ends with assistant, that's fine — the current user message follows
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseToolArguments(input: JsonValue): Map<String, Any?> {
        return try {
            val raw = input.convert(Map::class.java) as? Map<String, Any?> ?: emptyMap()
            raw
        } catch (e: Exception) {
            log.warn("Failed to parse tool arguments: {}", e.message)
            emptyMap()
        }
    }
}
