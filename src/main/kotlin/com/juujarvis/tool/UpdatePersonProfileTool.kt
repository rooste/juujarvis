package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.service.ConversationStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class UpdatePersonProfileTool(private val conversationStore: ConversationStore) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "update_person_profile"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Update your knowledge about a family member's preferences, communication style, " +
                "or important personal details. Use this immediately when someone explicitly tells you " +
                "how they want to be communicated with, or shares important personal information. " +
                "The observation will be appended to their existing profile."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "person_name" to mapOf(
                                    "type" to "string",
                                    "description" to "Name of the person (e.g., 'Dad', 'Mom')"
                                ),
                                "observation" to mapOf(
                                    "type" to "string",
                                    "description" to "What you learned — preference, communication style, important fact, or explicit instruction about how to interact with them"
                                )
                            )
                        )
                    )
                    .required(JsonValue.from(listOf("person_name", "observation")))
                    .build()
            )
            .build()
    }

    override fun execute(arguments: Map<String, Any?>): String {
        val personName = arguments["person_name"] as? String ?: return "Error: person_name is required"
        val observation = arguments["observation"] as? String ?: return "Error: observation is required"

        val personId = personName.lowercase().trim()
        val existing = conversationStore.loadPersonProfile(personId)

        val updatedProfile = if (existing != null) {
            "${existing.profile}\n- $observation"
        } else {
            "- $observation"
        }

        conversationStore.savePersonProfile(personId, updatedProfile)
        log.info("Updated profile for '{}': {}", personName, observation)
        return "Noted — updated profile for $personName."
    }
}
