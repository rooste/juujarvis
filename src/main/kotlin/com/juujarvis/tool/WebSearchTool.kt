package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.service.BraveSearchService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class WebSearchTool(private val braveSearchService: BraveSearchService) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "web_search"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Search the web for current information. Use this when someone asks a question you don't know " +
                "the answer to, needs up-to-date information (weather, news, prices, events, etc.), or when " +
                "you need to look something up to help the family."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "query" to mapOf(
                                    "type" to "string",
                                    "description" to "The search query"
                                ),
                                "count" to mapOf(
                                    "type" to "integer",
                                    "description" to "Number of results to return (default 5, max 10)"
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
        val query = arguments["query"] as? String ?: return "Error: query is required"
        val count = when (val c = arguments["count"]) {
            is Number -> c.toInt().coerceIn(1, 10)
            else -> 5
        }

        if (!braveSearchService.isConfigured()) {
            return "Web search is not configured — Brave Search API key is missing."
        }

        log.info("Web search: '{}' (count={})", query, count)
        val results = braveSearchService.search(query, count)

        if (results.isEmpty()) return "No results found for: $query"

        return results.mapIndexed { i, r ->
            "${i + 1}. ${r.title}\n   ${r.url}\n   ${r.description}"
        }.joinToString("\n\n")
    }
}
