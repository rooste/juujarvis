package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.service.ConversationStore
import com.juujarvis.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Which room each Echo is in and who usually uses it, so voice requests know where "here" and "me" are. */
@Component
class AlexaDeviceTool(
    private val conversationStore: ConversationStore,
    private val userService: UserService
) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)
    private val displayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("America/Chicago"))

    override val name = "alexa_device"

    override fun definition(): Tool = Tool.builder()
        .name(name)
        .description(
            "List the family's Echo devices that have talked to you, or save which room an Echo is in and who " +
            "usually uses it. In a voice turn the current Echo's device id is given in the context."
        )
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(JsonValue.from(mapOf(
                    "action" to mapOf("type" to "string", "enum" to listOf("list", "set"), "description" to "Action"),
                    "device_id" to mapOf("type" to "string", "description" to "Echo device id (required for set)"),
                    "room" to mapOf("type" to "string", "description" to "Room name, e.g. Kitchen"),
                    "default_person" to mapOf(
                        "type" to "string",
                        "description" to "Family member who usually uses this Echo (name), or 'none' for shared rooms"
                    )
                )))
                .required(JsonValue.from(listOf("action")))
                .build()
        )
        .build()

    override fun execute(arguments: Map<String, Any?>): String {
        return when (arguments["action"] as? String) {
            "list" -> {
                val devices = conversationStore.listAlexaDevices()
                if (devices.isEmpty()) return "No Echo has talked to you yet."
                devices.joinToString("\n") { d ->
                    val person = d.defaultUserId?.ifBlank { null }?.let { userService.getUser(it)?.name ?: it } ?: "shared"
                    "- ${d.room ?: "(room unknown)"} — usual person: $person — last used ${displayFormat.format(d.lastSeen)} — device_id ${d.deviceId}"
                }
            }
            "set" -> {
                val deviceId = (arguments["device_id"] as? String)?.trim() ?: return "Error: device_id is required"
                val room = (arguments["room"] as? String)?.trim()?.take(40)?.ifBlank { null }
                val personArg = (arguments["default_person"] as? String)?.trim()?.ifBlank { null }
                val userId = when {
                    personArg == null -> null
                    personArg.equals("none", ignoreCase = true) -> ""
                    else -> (userService.findByName(personArg) ?: userService.getUser(personArg))?.id
                        ?: return "Error: no family member named '$personArg'"
                }
                if (room == null && userId == null) return "Error: give a room or a default_person"
                if (conversationStore.findAlexaDevice(deviceId) == null) return "Error: unknown device_id"
                conversationStore.updateAlexaDevice(deviceId, room, userId)
                log.info("Alexa device updated: room={}, default user={}", room, userId)
                "Saved: this Echo is ${room?.let { "in the $it" } ?: "unchanged room"}" +
                    when (userId) { null -> ""; "" -> ", shared"; else -> ", usually used by ${personArg}" }
            }
            else -> "Error: action must be list or set"
        }
    }
}
