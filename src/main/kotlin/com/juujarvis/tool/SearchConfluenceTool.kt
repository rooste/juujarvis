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
                "documentation, notes, plans, or any information that might be stored in Confluence. " +
                "You can list spaces, list pages in a space, search by keywords, or fetch a specific page."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "action" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("search", "list_spaces", "list_pages", "get_page"),
                                    "description" to "Action to perform: 'search' for keyword search, 'list_spaces' to see available spaces, 'list_pages' to list pages in a space, 'get_page' to fetch full page content"
                                ),
                                "query" to mapOf(
                                    "type" to "string",
                                    "description" to "Search keywords (required for 'search' action)"
                                ),
                                "space_key" to mapOf(
                                    "type" to "string",
                                    "description" to "Confluence space key to scope the search or list pages (e.g., 'TOURDETEXA')"
                                ),
                                "page_id" to mapOf(
                                    "type" to "string",
                                    "description" to "Page ID to fetch full content (required for 'get_page' action)"
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
            "list_spaces" -> {
                val spaces = confluenceService.listSpaces()
                if (spaces.isEmpty()) return "No Confluence spaces found"
                log.info("Listed {} Confluence spaces", spaces.size)
                spaces.joinToString("\n") { "- ${it.name} (key: ${it.key})" }
            }

            "list_pages" -> {
                val spaceKey = arguments["space_key"] as? String
                    ?: return "Error: space_key is required for list_pages"
                val pages = confluenceService.listPagesInSpace(spaceKey)
                if (pages.isEmpty()) return "No pages found in space $spaceKey"
                log.info("Listed {} pages in space {}", pages.size, spaceKey)
                pages.joinToString("\n") { "- ${it.title} (id: ${it.id})" }
            }

            "get_page" -> {
                val pageId = arguments["page_id"] as? String
                    ?: return "Error: page_id is required for get_page"
                val page = confluenceService.getPageContent(pageId)
                    ?: return "Could not find page with ID $pageId"
                log.info("Fetched Confluence page '{}' ({} chars)", page.title, page.content.length)
                "**${page.title}**\n${page.url}\n\n${page.content}"
            }

            "search" -> {
                val query = arguments["query"] as? String
                    ?: return "Error: query is required for search"
                val spaceKey = arguments["space_key"] as? String
                val results = confluenceService.search(query, spaceKey)
                if (results.isEmpty()) return "No Confluence pages found for '$query'"
                log.info("Confluence search '{}': {} results", query, results.size)
                results.joinToString("\n\n") { r ->
                    "**${r.title}** (id: ${r.id})\n${r.url}\n${r.excerpt}"
                }
            }

            else -> "Unknown action: $action. Use search, list_spaces, list_pages, or get_page."
        }
    }
}
