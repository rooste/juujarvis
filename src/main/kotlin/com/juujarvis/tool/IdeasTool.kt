package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.time.LocalDate

/**
 * Read access to the Ideas Lab (a separate Claude Code workspace for business ideas)
 * and the ability to append raw ideas to its backlog Inbox.
 */
@Component
class IdeasTool(
    @Value("\${juujarvis.ideas.dir:\${user.home}/ideas}")
    private val ideasDir: String
) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "manage_ideas"

    private val slugPattern = Regex("^[a-z0-9][a-z0-9-]*$")

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Access Heikki's Ideas Lab, where future product and business ideas are explored and reviewed " +
                "by a team of agents. Use 'add' only when someone explicitly asks to save an idea to the backlog. " +
                "Use 'backlog' to see raw and ranked ideas, 'list' to see developed ideas, and 'read' to see an " +
                "idea's one-pager (including score, risks, and next experiment)."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "action" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("add", "backlog", "list", "read"),
                                    "description" to "Action to perform"
                                ),
                                "title" to mapOf(
                                    "type" to "string",
                                    "description" to "Short idea title (required for add)"
                                ),
                                "description" to mapOf(
                                    "type" to "string",
                                    "description" to "One or two sentences describing the idea: the problem and who has it (for add)"
                                ),
                                "added_by" to mapOf(
                                    "type" to "string",
                                    "description" to "Name of the person who suggested the idea (for add)"
                                ),
                                "slug" to mapOf(
                                    "type" to "string",
                                    "description" to "Idea folder name from 'list' (required for read)"
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
        if (!File(ideasDir).isDirectory) return "Error: Ideas Lab not found at $ideasDir"

        return when (action) {
            "add" -> handleAdd(arguments)
            "backlog" -> readFile(File(ideasDir, "backlog.md"))
            "list" -> handleList()
            "read" -> handleRead(arguments)
            else -> "Unknown action: $action. Use add, backlog, list, or read."
        }
    }

    private fun handleAdd(args: Map<String, Any?>): String {
        val title = (args["title"] as? String)?.singleLine() ?: return "Error: title is required"
        val description = (args["description"] as? String)?.singleLine()
        val addedBy = (args["added_by"] as? String)?.singleLine() ?: "unknown"

        val backlog = File(ideasDir, "backlog.md")
        if (!backlog.exists()) return "Error: backlog.md not found in $ideasDir"

        val entry = buildString {
            append("- [${LocalDate.now()}] $title")
            description?.let { append(" — $it") }
            append(" (from: $addedBy via Juujarvis)")
        }

        val lines = backlog.readLines().toMutableList()
        val inboxIdx = lines.indexOfFirst { it.trim() == "## Inbox" }
        if (inboxIdx < 0) return "Error: backlog.md has no '## Inbox' section"

        // Insert after the last line of the Inbox section (before the next heading), skipping trailing blanks
        val nextHeading = (inboxIdx + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        var insertAt = nextHeading
        while (insertAt > inboxIdx + 1 && lines[insertAt - 1].isBlank()) insertAt--
        lines.add(insertAt, entry)

        backlog.writeText(lines.joinToString("\n", postfix = "\n"))
        log.info("Added idea to backlog: {}", entry)
        return "Added to Ideas Lab backlog Inbox: $entry"
    }

    private fun handleList(): String {
        val folders = File(ideasDir, "ideas").listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            .orEmpty()
        if (folders.isEmpty()) return "No developed ideas yet (only the backlog)"

        return folders.joinToString("\n") { dir ->
            val idea = File(dir, "idea.md")
            val header = if (idea.exists()) idea.readLines() else emptyList()
            val title = header.firstOrNull { it.startsWith("# ") }?.removePrefix("# ") ?: dir.name
            val status = header.firstOrNull { it.startsWith("**Status:**") }?.removePrefix("**Status:**")?.trim()
            val score = header.firstOrNull { it.startsWith("**Score:**") }?.removePrefix("**Score:**")?.trim()
            buildString {
                append("${dir.name}: $title")
                status?.let { append(" | Status: $it") }
                score?.let { append(" | Score: $it") }
            }
        }
    }

    private fun handleRead(args: Map<String, Any?>): String {
        val slug = args["slug"] as? String ?: return "Error: slug is required for read"
        if (!slugPattern.matches(slug)) return "Error: invalid slug '$slug'"
        return readFile(File(ideasDir, "ideas/$slug/idea.md"))
    }

    private fun readFile(file: File): String {
        if (!file.exists()) return "Not found: ${file.relativeTo(File(ideasDir))}"
        return file.readText()
    }

    private fun String.singleLine(): String? =
        replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
}
