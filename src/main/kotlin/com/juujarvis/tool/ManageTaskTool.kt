package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.service.ConversationStore
import com.juujarvis.service.JuujarvisTask
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
class ManageTaskTool(private val conversationStore: ConversationStore) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)
    private val displayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("America/Chicago"))

    override val name = "manage_task"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Create, list, update, or complete tasks. Use this to track things the family has asked you to do, " +
                "follow up on, or remember. When someone asks you to do something that isn't immediate, create a task. " +
                "When a task is done, mark it completed."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "action" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("create", "list", "complete", "update"),
                                    "description" to "Action to perform"
                                ),
                                "title" to mapOf(
                                    "type" to "string",
                                    "description" to "Task title (required for create)"
                                ),
                                "description" to mapOf(
                                    "type" to "string",
                                    "description" to "Detailed description of what needs to be done"
                                ),
                                "assigned_to" to mapOf(
                                    "type" to "string",
                                    "description" to "Name of the person this task is for (e.g., 'Dad', 'Mom')"
                                ),
                                "task_id" to mapOf(
                                    "type" to "integer",
                                    "description" to "Task ID (required for complete and update)"
                                ),
                                "status" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("open", "completed"),
                                    "description" to "Filter by status for list, or new status for update"
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

        return when (action) {
            "create" -> {
                val title = arguments["title"] as? String ?: return "Error: title is required"
                val description = arguments["description"] as? String
                val assignedTo = arguments["assigned_to"] as? String

                val task = JuujarvisTask(
                    title = title,
                    description = description,
                    assignedTo = assignedTo,
                    createdAt = Instant.now()
                )
                val id = conversationStore.saveTask(task)
                log.info("Created task #{}: {}", id, title)
                "Created task #$id: $title" + (assignedTo?.let { " (assigned to $it)" } ?: "")
            }

            "list" -> {
                val status = arguments["status"] as? String
                val tasks = if (status != null) {
                    conversationStore.loadTasksByStatus(status)
                } else {
                    conversationStore.loadTasksByStatus("open")
                }
                if (tasks.isEmpty()) return "No ${status ?: "open"} tasks"
                tasks.joinToString("\n") { formatTask(it) }
            }

            "complete" -> {
                val taskId = parseTaskId(arguments) ?: return "Error: task_id is required"
                conversationStore.completeTask(taskId)
                log.info("Completed task #{}", taskId)
                "Task #$taskId marked as completed"
            }

            "update" -> {
                val taskId = parseTaskId(arguments) ?: return "Error: task_id is required"
                val title = arguments["title"] as? String
                val description = arguments["description"] as? String
                val assignedTo = arguments["assigned_to"] as? String
                val status = arguments["status"] as? String

                conversationStore.updateTask(taskId, title, description, assignedTo, status)
                log.info("Updated task #{}", taskId)
                "Task #$taskId updated"
            }

            else -> "Unknown action: $action. Use create, list, complete, or update."
        }
    }

    private fun formatTask(task: JuujarvisTask): String {
        val assigned = task.assignedTo?.let { " → $it" } ?: ""
        val desc = task.description?.let { " — $it" } ?: ""
        val created = displayFormat.format(task.createdAt)
        val completed = task.completedAt?.let { " (completed ${displayFormat.format(it)})" } ?: ""
        return "#${task.id} [${task.status}] ${task.title}$assigned$desc (created $created)$completed"
    }

    private fun parseTaskId(arguments: Map<String, Any?>): Long? {
        return when (val v = arguments["task_id"]) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }
}
