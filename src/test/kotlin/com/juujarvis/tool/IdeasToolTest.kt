package com.juujarvis.tool

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeasToolTest {

    @TempDir
    lateinit var dir: File

    private fun backlog() = File(dir, "backlog.md")

    private fun tool(): IdeasTool {
        backlog().writeText(
            """
            # Idea Backlog

            ## Inbox
            <!-- comment -->

            ## Ranked
            | Score | Idea |
            """.trimIndent() + "\n"
        )
        return IdeasTool(dir.absolutePath)
    }

    @Test
    fun `add appends entries inside the Inbox section in order`() {
        val tool = tool()
        tool.execute(mapOf("action" to "add", "title" to "First", "description" to "multi\nline", "added_by" to "Dad"))
        tool.execute(mapOf("action" to "add", "title" to "Second"))

        val lines = backlog().readLines()
        val today = LocalDate.now()
        val inbox = lines.indexOf("## Inbox")
        assertEquals("<!-- comment -->", lines[inbox + 1])
        assertEquals("- [$today] First — multi line (from: Dad via Juujarvis)", lines[inbox + 2])
        assertEquals("- [$today] Second (from: unknown via Juujarvis)", lines[inbox + 3])
        assertEquals("", lines[inbox + 4])
        assertEquals("## Ranked", lines[inbox + 5])
    }

    @Test
    fun `read rejects path traversal and list reads idea headers`() {
        val tool = tool()
        assertTrue(tool.execute(mapOf("action" to "read", "slug" to "../secrets")).startsWith("Error"))

        File(dir, "ideas/family-ai").mkdirs()
        File(dir, "ideas/family-ai/idea.md").writeText("# Family AI\n\n**Status:** reviewed\n**Score:** 17/25\n")
        assertEquals(
            "family-ai: Family AI | Status: reviewed | Score: 17/25",
            tool.execute(mapOf("action" to "list"))
        )
    }
}
