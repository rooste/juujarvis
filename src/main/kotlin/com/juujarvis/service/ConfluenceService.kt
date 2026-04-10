package com.juujarvis.service

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.util.Base64

@Service
@ConditionalOnProperty("juujarvis.confluence.base-url", matchIfMissing = false)
class ConfluenceService(
    @Value("\${juujarvis.confluence.base-url}")
    private val baseUrl: String,
    @Value("\${juujarvis.confluence.email}")
    private val email: String,
    @Value("\${juujarvis.confluence.api-token}")
    private val apiToken: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(baseUrl.trimEnd('/'))
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " +
            Base64.getEncoder().encodeToString("$email:$apiToken".toByteArray()))
        .defaultHeader(HttpHeaders.ACCEPT, "application/json")
        .build()

    fun search(query: String, limit: Int = 5): List<SearchResult> {
        log.info("Searching Confluence: '{}'", query)
        val cql = "type=page AND text~\"$query\""
        val response = restClient.get()
            .uri("/wiki/rest/api/content/search?cql={cql}&limit={limit}&expand=excerpt", cql, limit)
            .retrieve()
            .body(JsonNode::class.java)
            ?: return emptyList()

        return response["results"]?.map { node ->
            SearchResult(
                id = node["id"].asText(),
                title = node["title"].asText(),
                excerpt = node["excerpt"]?.asText()?.replace(Regex("<[^>]*>"), "") ?: "",
                url = "$baseUrl/wiki${node["_links"]?.get("webui")?.asText() ?: ""}"
            )
        } ?: emptyList()
    }

    fun getPageContent(pageId: String): PageContent? {
        log.info("Fetching Confluence page: {}", pageId)
        val response = restClient.get()
            .uri("/wiki/rest/api/content/{id}?expand=body.storage,version", pageId)
            .retrieve()
            .body(JsonNode::class.java)
            ?: return null

        val html = response["body"]?.get("storage")?.get("value")?.asText() ?: ""
        // Strip HTML tags for a readable text version
        val text = html.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return PageContent(
            id = response["id"].asText(),
            title = response["title"].asText(),
            content = text,
            url = "$baseUrl/wiki${response["_links"]?.get("webui")?.asText() ?: ""}"
        )
    }

    data class SearchResult(val id: String, val title: String, val excerpt: String, val url: String)
    data class PageContent(val id: String, val title: String, val content: String, val url: String)
}
