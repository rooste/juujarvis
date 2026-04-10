package com.juujarvis.service

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.*
import com.juujarvis.messaging.MessagingService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

@Service
class DailySummaryService(
    private val conversationStore: ConversationStore,
    private val anthropicClient: AnthropicClient,
    private val messagingService: MessagingService,
    private val userService: UserService,
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private val googleCalendarService: GoogleCalendarService?,
    @Value("\${juujarvis.anthropic.model:claude-sonnet-4-20250514}")
    private val modelId: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 4 * * *")
    fun generateDailySummary() {
        val yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1)
        val startOfYesterday = yesterday.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val startOfToday = yesterday.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

        log.info("Generating daily summary for {}", yesterday)

        val turns = try {
            conversationStore.loadTurnsSince(startOfYesterday)
                .filter { it.timestamp < startOfToday }
        } catch (e: Exception) {
            log.error("Failed to load turns for summary: {}", e.message)
            return
        }

        if (turns.isEmpty()) {
            log.info("No conversations yesterday ({}), skipping summary", yesterday)
            return
        }

        val transcript = turns
            .groupBy { it.conversationId }
            .entries
            .joinToString("\n\n---\n\n") { (convId, convTurns) ->
                "Conversation: $convId\n" + convTurns.joinToString("\n") { turn ->
                    val name = turn.senderName ?: turn.role
                    "[$name]: ${turn.content}"
                }
            }

        val participants = turns.mapNotNull { it.senderName }.distinct()

        log.info("Summarizing {} turns across {} conversations", turns.size, turns.map { it.conversationId }.distinct().size)

        // Step 1: Generate daily summary
        try {
            val params = MessageCreateParams.builder()
                .model(Model.of(modelId))
                .maxTokens(1024L)
                .system("""You are summarizing a day of family conversations for the Juujarvis household AI assistant.
Produce:
1. A concise summary of what was discussed (3-5 bullet points max)
2. Any follow-up items, promises made, or pending requests (as a simple bulleted list, or "None" if nothing pending)
Keep it brief — this will be injected into future system prompts for context.""")
                .addMessage(
                    MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(MessageParam.Content.ofString("Here are yesterday's ($yesterday) conversations:\n\n$transcript"))
                        .build()
                )
                .build()

            val response = anthropicClient.messages().create(params)

            val summaryText = response.content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("\n")

            if (summaryText.isNotBlank()) {
                conversationStore.saveDailySummary(yesterday, summaryText, null)
                log.info("Daily summary saved for {}: {} chars", yesterday, summaryText.length)
            } else {
                log.warn("Claude returned empty summary for {}", yesterday)
            }
        } catch (e: Exception) {
            log.error("Failed to generate daily summary for {}: {}", yesterday, e.message)
        }

        // Step 2: Update person profiles
        if (participants.isNotEmpty()) {
            updatePersonProfiles(transcript, participants)
        }

        // Step 3: Calendar reminders
        generateCalendarReminders()
    }

    private fun generateCalendarReminders() {
        if (googleCalendarService == null) {
            log.debug("Google Calendar not configured, skipping reminders")
            return
        }

        log.info("Planning calendar reminders")
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        try {
            // Get next 2 days for immediate reminders
            val upcomingEvents = googleCalendarService.getUpcomingEvents(2)
            // Get rest of the week for big event early warnings
            val weekEvents = googleCalendarService.getUpcomingEvents(7)

            if (upcomingEvents.isEmpty() && weekEvents.isEmpty()) {
                log.info("No upcoming calendar events, skipping reminders")
                return
            }

            val upcomingFormatted = upcomingEvents.joinToString("\n") { e ->
                val date = e.start.atZone(zone).toLocalDate()
                val dayLabel = when (date) {
                    today -> "Today"
                    today.plusDays(1) -> "Tomorrow"
                    else -> date.toString()
                }
                "$dayLabel: ${e.formatForDisplay(zone)}"
            }

            val laterThisWeek = weekEvents.filter {
                val eventDate = it.start.atZone(zone).toLocalDate()
                eventDate.isAfter(today.plusDays(1))
            }
            val laterFormatted = if (laterThisWeek.isNotEmpty()) {
                "\n\nLater this week:\n" + laterThisWeek.joinToString("\n") { it.formatForDisplay(zone) }
            } else ""

            val familyMembers = userService.getAllUsers().joinToString(", ") { it.name }
            val nowTime = java.time.LocalTime.now(zone).format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))

            // Find recent group chats for Claude to target
            val recentGroups = conversationStore.loadRecentGroupChats(days = 7)
            val groupContext = if (recentGroups.isNotEmpty()) {
                "\n\nKnown group chats (use GROUP: chat_guid to send to a group):\n" +
                    recentGroups.joinToString("\n") { g ->
                        val label = g.displayName ?: g.conversationId
                        val members = if (g.members.isNotEmpty()) " — members: ${g.members.joinToString(", ")}" else ""
                        "- $label (guid: ${g.chatGuid})$members"
                    }
            } else ""

            val params = MessageCreateParams.builder()
                .model(Model.of(modelId))
                .maxTokens(1024L)
                .system("""You are Juujarvis, the family AI assistant, planning reminders for today.

Current time: $nowTime. Review the upcoming calendar events and schedule reminders at appropriate times:
- Morning events: remind at 7:00 AM
- Afternoon events: remind at 7:00 AM (as part of morning digest) AND 1 hour before the event
- Evening events: remind 2 hours before
- All-day events: remind at 7:00 AM
- Big events later this week (travel, parties, important appointments): remind at 7:00 AM today as a heads-up
- Don't schedule reminders for events that have already passed
- Don't schedule reminders in the past (earliest allowed: now)
- Keep reminders warm, brief, and helpful
- If multiple events for the same person at the same time, combine into one message
- If an event involves the whole family or multiple people, send the reminder to a group chat instead of individuals
- If an event description mentions specific attendees and there is a group chat whose members exactly match those attendees (no extra, no missing), prefer sending to that group chat

Family members: $familyMembers$groupContext

Output format — one line per scheduled reminder:
For individual: REMIND: person_name | YYYY-MM-DDTHH:MM | message text
For group chat: GROUP: chat_guid | YYYY-MM-DDTHH:MM | message text

Example:
REMIND: Dad | ${today}T07:00 | Good morning! You have a dentist appointment at 2pm today.
REMIND: Dad | ${today}T13:00 | Heads up — dentist appointment in an hour!
GROUP: iMessage;+;chat123456 | ${today}T07:00 | Good morning family! Reminder: dinner at Grandma's tonight at 6pm.

If no reminders are needed, output: NO_REMINDERS""")
                .addMessage(
                    MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(MessageParam.Content.ofString("Today is $today. Here are the upcoming events:\n\n$upcomingFormatted$laterFormatted"))
                        .build()
                )
                .build()

            val response = anthropicClient.messages().create(params)
            val reminderText = response.content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("\n")

            if (reminderText.contains("NO_REMINDERS")) {
                log.info("No calendar reminders needed today")
                return
            }

            // Parse REMIND: and GROUP: lines
            val remindPattern = Regex("""REMIND:\s*(.+?)\s*\|\s*(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})\s*\|\s*(.+)""")
            val groupPattern = Regex("""GROUP:\s*(.+?)\s*\|\s*(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})\s*\|\s*(.+)""")
            var count = 0

            remindPattern.findAll(reminderText).forEach { match ->
                val name = match.groupValues[1].trim()
                val sendAt = java.time.LocalDateTime.parse(match.groupValues[2]).atZone(zone).toInstant()
                val message = match.groupValues[3].trim()

                if (message.isNotBlank() && sendAt.isAfter(java.time.Instant.now().minusSeconds(60))) {
                    conversationStore.saveReminder(name, message, sendAt, "user")
                    log.info("Scheduled reminder for '{}' at {}: {}", name, sendAt, message.take(80))
                    count++
                }
            }

            groupPattern.findAll(reminderText).forEach { match ->
                val chatGuid = match.groupValues[1].trim()
                val sendAt = java.time.LocalDateTime.parse(match.groupValues[2]).atZone(zone).toInstant()
                val message = match.groupValues[3].trim()

                if (message.isNotBlank() && sendAt.isAfter(java.time.Instant.now().minusSeconds(60))) {
                    conversationStore.saveReminder(chatGuid, message, sendAt, "group")
                    log.info("Scheduled group reminder for '{}' at {}: {}", chatGuid, sendAt, message.take(80))
                    count++
                }
            }

            log.info("Scheduled {} calendar reminders", count)
        } catch (e: Exception) {
            log.error("Failed to generate calendar reminders: {}", e.message)
        }
    }

    private fun updatePersonProfiles(transcript: String, participants: List<String>) {
        log.info("Updating person profiles for: {}", participants)

        val existingProfiles = participants.mapNotNull { name ->
            conversationStore.loadPersonProfile(name.lowercase())
        }.joinToString("\n\n") { p ->
            "${p.personId}:\n${p.profile}"
        }

        val existingContext = if (existingProfiles.isNotBlank()) {
            "\n\nExisting profiles:\n$existingProfiles"
        } else ""

        try {
            val params = MessageCreateParams.builder()
                .model(Model.of(modelId))
                .maxTokens(1024L)
                .system("""You are analyzing family conversations to build profiles of each person for the Juujarvis household AI assistant.

For each person who participated, produce an updated profile that captures:
- Communication style and preferences (brief vs detailed, formal vs casual, language preferences)
- Interests, routines, and recurring topics
- Explicit instructions they gave about how to communicate with them
- Important personal facts (schedule patterns, responsibilities, etc.)

Output format — one section per person:
PERSON: name
- bullet point observations

Only include meaningful observations. Skip generic things. Build on existing profiles — keep what's still relevant, add new insights, remove anything contradicted by new evidence.$existingContext""")
                .addMessage(
                    MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(MessageParam.Content.ofString("Here are yesterday's conversations:\n\n$transcript\n\nUpdate profiles for: ${participants.joinToString(", ")}"))
                        .build()
                )
                .build()

            val response = anthropicClient.messages().create(params)

            val profileText = response.content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("\n")

            if (profileText.isBlank()) {
                log.warn("Claude returned empty profile updates")
                return
            }

            // Parse PERSON: sections and save each
            val personPattern = Regex("""PERSON:\s*(.+)""", RegexOption.IGNORE_CASE)
            val sections = profileText.split(personPattern).drop(1) // drop text before first PERSON:
            val names = personPattern.findAll(profileText).map { it.groupValues[1].trim() }.toList()

            names.zip(sections).forEach { (name, content) ->
                val trimmed = content.trim()
                if (trimmed.isNotBlank()) {
                    conversationStore.savePersonProfile(name.lowercase(), trimmed)
                    log.info("Updated profile for '{}': {} chars", name, trimmed.length)
                }
            }
        } catch (e: Exception) {
            log.error("Failed to update person profiles: {}", e.message)
        }
    }
}
