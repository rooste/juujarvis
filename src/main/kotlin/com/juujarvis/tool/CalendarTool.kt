package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CalendarTool : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "manage_calendar"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Manage family calendar events. Can create, list, or delete events. " +
                "Use this when someone asks about the schedule, wants to add an event, " +
                "or needs to check what's coming up."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "action" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("create", "list", "delete"),
                                    "description" to "The calendar action to perform"
                                ),
                                "title" to mapOf(
                                    "type" to "string",
                                    "description" to "Event title (for create)"
                                ),
                                "date" to mapOf(
                                    "type" to "string",
                                    "description" to "Event date in YYYY-MM-DD format"
                                ),
                                "time" to mapOf(
                                    "type" to "string",
                                    "description" to "Event time in HH:MM format (24h)"
                                ),
                                "duration_minutes" to mapOf(
                                    "type" to "integer",
                                    "description" to "Duration in minutes (default 60)"
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
        val action = arguments["action"] as? String ?: return "Error: action is required"

        log.info("Calendar tool executing: action={}, args={}", action, arguments)

        // TODO: Replace with Google Calendar API calls
        return when (action) {
            "create" -> {
                val title = arguments["title"] ?: "Untitled"
                val date = arguments["date"] ?: "unspecified date"
                val time = arguments["time"] ?: "unspecified time"
                "[STUB] Created calendar event: '$title' on $date at $time"
            }
            "list" -> {
                val date = arguments["date"] ?: "today"
                "[STUB] Calendar for $date:\n" +
                    "- 09:00 School drop-off\n" +
                    "- 14:00 Dentist appointment\n" +
                    "- 18:00 Family dinner"
            }
            "delete" -> {
                val title = arguments["title"] ?: "unknown"
                "[STUB] Deleted calendar event: '$title'"
            }
            else -> "Error: unknown action '$action'"
        }
    }
}
