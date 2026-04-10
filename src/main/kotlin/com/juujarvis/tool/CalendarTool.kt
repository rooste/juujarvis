package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.service.GoogleCalendarService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component
import java.time.*
import java.time.format.DateTimeFormatter

@Component
@ConditionalOnBean(GoogleCalendarService::class)
class CalendarTool(private val calendarService: GoogleCalendarService) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.systemDefault()

    override val name = "manage_calendar"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Manage the family Google Calendar. Actions:\n" +
                "- list: Show events for a date range (default: today + tomorrow)\n" +
                "- search: Find events by keyword, looks up to 6 months ahead\n" +
                "- create: Add a new event\n" +
                "- update: Modify an existing event (need event_id from list/search)\n" +
                "- delete: Remove an event (need event_id from list/search)\n" +
                "Use this when someone asks about the schedule, upcoming events, or wants to create/change events.\n" +
                "When creating events, always include who is expected to attend in the description field if mentioned."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "action" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("list", "search", "create", "update", "delete"),
                                    "description" to "The calendar action to perform"
                                ),
                                "query" to mapOf(
                                    "type" to "string",
                                    "description" to "Search keywords (for search action)"
                                ),
                                "date" to mapOf(
                                    "type" to "string",
                                    "description" to "Start date in YYYY-MM-DD format. For list: defaults to today"
                                ),
                                "days" to mapOf(
                                    "type" to "integer",
                                    "description" to "Number of days to list (default: 2 for today+tomorrow, max 90)"
                                ),
                                "title" to mapOf(
                                    "type" to "string",
                                    "description" to "Event title (for create/update)"
                                ),
                                "time" to mapOf(
                                    "type" to "string",
                                    "description" to "Event start time in HH:MM format, 24h (for create/update)"
                                ),
                                "end_time" to mapOf(
                                    "type" to "string",
                                    "description" to "Event end time in HH:MM format, 24h (for create/update). Defaults to start + 1 hour"
                                ),
                                "all_day" to mapOf(
                                    "type" to "boolean",
                                    "description" to "Whether this is an all-day event (for create)"
                                ),
                                "location" to mapOf(
                                    "type" to "string",
                                    "description" to "Event location (for create/update)"
                                ),
                                "description" to mapOf(
                                    "type" to "string",
                                    "description" to "Event description/notes (for create/update)"
                                ),
                                "event_id" to mapOf(
                                    "type" to "string",
                                    "description" to "Event ID (required for update/delete, get from list or search)"
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

        return try {
            when (action) {
                "list" -> executeList(arguments)
                "search" -> executeSearch(arguments)
                "create" -> executeCreate(arguments)
                "update" -> executeUpdate(arguments)
                "delete" -> executeDelete(arguments)
                else -> "Error: unknown action '$action'"
            }
        } catch (e: Exception) {
            log.error("Calendar tool error: {}", e.message, e)
            "Error: ${e.message}"
        }
    }

    private fun executeList(args: Map<String, Any?>): String {
        val startDate = (args["date"] as? String)?.let { LocalDate.parse(it) } ?: LocalDate.now(zone)
        val days = ((args["days"] as? Number)?.toInt() ?: 2).coerceIn(1, 90)
        val from = startDate.atStartOfDay(zone).toInstant()
        val to = startDate.plusDays(days.toLong()).atStartOfDay(zone).toInstant()

        val events = calendarService.listEvents(from, to)
        if (events.isEmpty()) return "No events from ${startDate} to ${startDate.plusDays(days.toLong() - 1)}"

        log.info("Listed {} events for {} days starting {}", events.size, days, startDate)

        // Group by date
        return events.groupBy { it.start.atZone(zone).toLocalDate() }
            .entries.joinToString("\n\n") { (date, dayEvents) ->
                val dateLabel = date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
                "$dateLabel:\n" + dayEvents.joinToString("\n") { e ->
                    val time = if (e.isAllDay) "  All day" else "  ${e.start.atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a"))}"
                    val loc = e.location?.let { " @ $it" } ?: ""
                    "$time — ${e.summary}$loc (id: ${e.id})"
                }
            }
    }

    private fun executeSearch(args: Map<String, Any?>): String {
        val query = args["query"] as? String ?: return "Error: query is required for search"
        val events = calendarService.searchEvents(query)
        if (events.isEmpty()) return "No upcoming events found matching '$query'"

        log.info("Found {} events for query '{}'", events.size, query)
        return events.joinToString("\n") { e ->
            "${e.formatForDisplay(zone)} (id: ${e.id})"
        }
    }

    private fun executeCreate(args: Map<String, Any?>): String {
        val title = args["title"] as? String ?: return "Error: title is required for create"
        val dateStr = args["date"] as? String ?: return "Error: date is required for create"
        val date = LocalDate.parse(dateStr)

        val timeStr = args["time"] as? String
        val startTime = if (timeStr != null) {
            val time = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("H:mm"))
            date.atTime(time).atZone(zone)
        } else {
            date.atStartOfDay(zone)
        }

        val endTimeStr = args["end_time"] as? String
        val endTime = if (endTimeStr != null) {
            val time = LocalTime.parse(endTimeStr, DateTimeFormatter.ofPattern("H:mm"))
            date.atTime(time).atZone(zone)
        } else {
            startTime.plusHours(1)
        }

        val location = args["location"] as? String
        val description = args["description"] as? String

        val event = calendarService.createEvent(title, startTime, endTime, description, location)
        return "Created: ${event.formatForDisplay(zone)} (id: ${event.id})"
    }

    private fun executeUpdate(args: Map<String, Any?>): String {
        val eventId = args["event_id"] as? String ?: return "Error: event_id is required for update"
        val title = args["title"] as? String
        val dateStr = args["date"] as? String
        val timeStr = args["time"] as? String
        val location = args["location"] as? String
        val description = args["description"] as? String

        val startTime = if (dateStr != null && timeStr != null) {
            val date = LocalDate.parse(dateStr)
            val time = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("H:mm"))
            date.atTime(time).atZone(zone)
        } else null

        val endTimeStr = args["end_time"] as? String
        val endTime = if (dateStr != null && endTimeStr != null) {
            val date = LocalDate.parse(dateStr)
            val time = LocalTime.parse(endTimeStr, DateTimeFormatter.ofPattern("H:mm"))
            date.atTime(time).atZone(zone)
        } else null

        val event = calendarService.updateEvent(eventId, title, startTime, endTime, description, location)
        return "Updated: ${event.formatForDisplay(zone)} (id: ${event.id})"
    }

    private fun executeDelete(args: Map<String, Any?>): String {
        val eventId = args["event_id"] as? String ?: return "Error: event_id is required for delete"
        calendarService.deleteEvent(eventId)
        return "Event deleted"
    }
}
