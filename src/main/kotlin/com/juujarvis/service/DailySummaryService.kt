package com.juujarvis.service

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.*
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
