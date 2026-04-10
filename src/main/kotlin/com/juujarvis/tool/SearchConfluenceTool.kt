package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.service.ConfluenceService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component

@Component
@ConditionalOnBean(ConfluenceService::class)
class SearchConfluenceTool(private val confluenceService: ConfluenceService) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "search_confluence"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Search the family's Confluence wiki for information. Use this when someone asks about " +
                "documentation, notes, plans, or any information that might be stored in Confluence."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "query" to mapOf(
                                    "type" to "string",
                                    "description" to "Search query — keywords or topic to look for"
                                ),
                                "page_id" to mapOf(
                                    "type" to "string",
                                    "description" to "If provided, fetch the full content of this specific page instead of searching"
                                )
                            )
                        )
                    )
                    .required(JsonValue.from(listOf("query")))
                    .build()
            )
            .build()
    }

    override fun execute(arguments: Map<String, Any?>): String {
        val pageId = arguments["page_id"] as? String
        if (pageId != null) {
            val page = confluenceService.getPageContent(pageId)
                ?: return "Could not find page with ID $pageId"
            log.info("Fetched Confluence page '{}' ({} chars)", page.title, page.content.length)
            return "**${page.title}**\n${page.url}\n\n${page.content}"
        }

        val query = arguments["query"] as? String ?: return "Error: query is required"
        val results = confluenceService.search(query)
        if (results.isEmpty()) return "No Confluence pages found for '$query'"

        log.info("Confluence search '{}': {} results", query, results.size)
        return results.joinToString("\n\n") { r ->
            "**${r.title}** (id: ${r.id})\n${r.url}\n${r.excerpt}"
        }
    }
}
