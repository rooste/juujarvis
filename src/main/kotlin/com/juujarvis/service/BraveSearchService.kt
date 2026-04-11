package com.juujarvis.service

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class BraveSearchService(
    @Value("\${juujarvis.brave.api-key:}")
    private val apiKey: String
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.builder()
        .baseUrl("https://api.search.brave.com/res/v1")
        .build()

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    fun search(query: String, count: Int = 5): List<SearchResult> {
        if (!isConfigured()) {
            log.warn("Brave Search API key not configured")
            return emptyList()
        }

        return try {
            val response = restClient.get()
                .uri { uri ->
                    uri.path("/web/search")
                        .queryParam("q", query)
                        .queryParam("count", count)
                        .build()
                }
                .header(HttpHeaders.ACCEPT, "application/json")
                .header("X-Subscription-Token", apiKey)
                .retrieve()
                .body(JsonNode::class.java)

            val results = response?.get("web")?.get("results") ?: return emptyList()
            results.map { node ->
                SearchResult(
                    title = node.get("title")?.asText() ?: "",
                    url = node.get("url")?.asText() ?: "",
                    description = node.get("description")?.asText() ?: ""
                )
            }
        } catch (e: Exception) {
            log.error("Brave Search failed for query '{}': {}", query, e.message)
            emptyList()
        }
    }

    data class SearchResult(
        val title: String,
        val url: String,
        val description: String
    )
}
