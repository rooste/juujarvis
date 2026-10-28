package com.juujarvis.service

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
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

    fun search(query: String, spaceKey: String? = null, limit: Int = 10): List<SearchResult> {
        log.info("Searching Confluence: '{}' in space={}", query, spaceKey ?: "all")
        val spaceCql = if (spaceKey != null) " AND space=\"$spaceKey\"" else ""
        val cql = "type=page AND (title~\"$query\" OR text~\"$query\")$spaceCql"
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

    fun listSpaces(): List<SpaceInfo> {
        log.info("Listing Confluence spaces")
        val response = restClient.get()
            .uri("/wiki/rest/api/space?type=global&limit=25")
            .retrieve()
            .body(JsonNode::class.java)
            ?: return emptyList()

        return response["results"]?.map { node ->
            SpaceInfo(
                key = node["key"].asText(),
                name = node["name"].asText()
            )
        } ?: emptyList()
    }

    fun listPagesInSpace(spaceKey: String, limit: Int = 25): List<SearchResult> {
        log.info("Listing pages in space: {}", spaceKey)
        val cql = "type=page AND space=\"$spaceKey\""
        val response = restClient.get()
            .uri("/wiki/rest/api/content/search?cql={cql}&limit={limit}", cql, limit)
            .retrieve()
            .body(JsonNode::class.java)
            ?: return emptyList()

        return response["results"]?.map { node ->
            SearchResult(
                id = node["id"].asText(),
                title = node["title"].asText(),
                excerpt = "",
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

        val storage = response["body"]?.get("storage")?.get("value")?.asText() ?: ""
        return PageContent(
            id = response["id"].asText(),
            title = response["title"].asText(),
            content = ConfluenceMarkup.toMarkdown(storage),   // readable, with tables and links
            url = "$baseUrl/wiki${response["_links"]?.get("webui")?.asText() ?: ""}",
            structure = ConfluenceMarkup.structure(storage),
            version = response["version"]?.get("number")?.asInt() ?: 0
        )
    }

    /** The page in storage format (XHTML), for edits that must keep tables, layouts and macros. */
    fun getPageSource(pageId: String): PageSource {
        val current = page(pageId)
        return PageSource(
            id = current["id"].asText(),
            title = current["title"].asText(),
            version = current["version"]?.get("number")?.asInt() ?: 0,
            storage = current["body"]?.get("storage")?.get("value")?.asText() ?: ""
        )
    }

    /**
     * Creates a page in a global (shared) space; personal spaces are refused. [markdown] is converted with
     * [ConfluenceMarkup]. Returns the new page.
     */
    fun createPage(spaceKey: String, title: String, markdown: String, parentId: String? = null): PageRef {
        requireGlobalSpace(spaceKey)
        if (parentId != null) {
            val parentSpace = page(parentId)["space"]?.get("key")?.asText()
            if (parentSpace != spaceKey) throw ConfluenceWriteException("parent page $parentId is not in space $spaceKey")
        }
        val body = mutableMapOf<String, Any>(
            "type" to "page",
            "title" to title.trim().take(255),
            "space" to mapOf("key" to spaceKey),
            "body" to mapOf("storage" to mapOf("value" to ConfluenceMarkup.toStorage(markdown), "representation" to "storage"))
        )
        if (parentId != null) body["ancestors"] = listOf(mapOf("id" to parentId))
        val response = restClient.post().uri("/wiki/rest/api/content")
            .contentType(MediaType.APPLICATION_JSON).body(body)
            .retrieve().body(JsonNode::class.java) ?: throw ConfluenceWriteException("no response")
        log.info("Created Confluence page {} in space {}", response["id"].asText(), spaceKey)
        return ref(response)
    }

    enum class UpdateMode { APPEND, REPLACE, SOURCE }

    /**
     * Updates a page in a global space. APPEND adds [content] (Markdown) at the end; REPLACE replaces the
     * page with it, refused on pages with tables, layouts or macros (Markdown can't keep them); SOURCE
     * replaces the page with [content] in storage format, refused if it removes tables or layout sections
     * unless [allowStructureLoss]. The update names the version it was based on, so a concurrent edit
     * makes it fail instead of being lost.
     */
    fun updatePage(pageId: String, content: String, mode: UpdateMode, newTitle: String? = null, allowStructureLoss: Boolean = false): PageRef {
        val current = page(pageId)
        val spaceKey = current["space"]?.get("key")?.asText() ?: throw ConfluenceWriteException("page has no space")
        requireGlobalSpace(spaceKey)
        val version = current["version"]?.get("number")?.asInt() ?: throw ConfluenceWriteException("page has no version")
        val existing = current["body"]?.get("storage")?.get("value")?.asText() ?: ""
        val before = ConfluenceMarkup.structure(existing)
        val newStorage = when (mode) {
            UpdateMode.APPEND -> existing + ConfluenceMarkup.toStorage(content)
            UpdateMode.REPLACE -> {
                if (before.isRich) throw ConfluenceWriteException(
                    "this page has $before, which a Markdown replace would destroy. Use get_page_source, change only " +
                    "what is needed, and save with mode 'source'")
                ConfluenceMarkup.toStorage(content)
            }
            UpdateMode.SOURCE -> {
                ConfluenceMarkup.checkSource(content)?.let { throw ConfluenceWriteException(it) }
                val after = ConfluenceMarkup.structure(content)
                if (!allowStructureLoss && (after.tables < before.tables || after.layoutSections < before.layoutSections))
                    throw ConfluenceWriteException(
                        "the new content has $after but the page has $before. Keep the existing tables and layout " +
                        "sections, or ask the person to confirm removing them and retry with allow_structure_loss")
                content
            }
        }
        val body = mapOf(
            "id" to pageId,
            "type" to "page",
            "title" to (newTitle?.trim()?.take(255)?.ifEmpty { null } ?: current["title"].asText()),
            "version" to mapOf("number" to version + 1, "message" to "Updated by Juujarvis"),
            "body" to mapOf("storage" to mapOf("value" to newStorage, "representation" to "storage"))
        )
        val response = restClient.put().uri("/wiki/rest/api/content/{id}", pageId)
            .contentType(MediaType.APPLICATION_JSON).body(body)
            .retrieve().body(JsonNode::class.java) ?: throw ConfluenceWriteException("no response")
        log.info("Updated Confluence page {} ({}) to version {}", pageId, mode, version + 1)
        return ref(response)
    }

    private fun page(pageId: String): JsonNode {
        if (!pageId.matches(Regex("\\d{1,20}"))) throw ConfluenceWriteException("invalid page id")
        return restClient.get().uri("/wiki/rest/api/content/{id}?expand=body.storage,version,space", pageId)
            .retrieve().body(JsonNode::class.java) ?: throw ConfluenceWriteException("page $pageId not found")
    }

    private fun requireGlobalSpace(spaceKey: String) {
        if (spaceKey.startsWith("~")) throw ConfluenceWriteException("personal spaces are read-only for Juujarvis")
        val space = restClient.get().uri("/wiki/rest/api/space/{key}", spaceKey)
            .retrieve().body(JsonNode::class.java) ?: throw ConfluenceWriteException("space $spaceKey not found")
        if (space["type"]?.asText() != "global") throw ConfluenceWriteException("personal spaces are read-only for Juujarvis")
    }

    private fun ref(node: JsonNode) = PageRef(
        id = node["id"].asText(),
        title = node["title"].asText(),
        url = "$baseUrl/wiki${node["_links"]?.get("webui")?.asText() ?: ""}"
    )

    class ConfluenceWriteException(message: String) : RuntimeException(message)

    data class PageRef(val id: String, val title: String, val url: String)
    data class SpaceInfo(val key: String, val name: String)
    data class SearchResult(val id: String, val title: String, val excerpt: String, val url: String)
    data class PageContent(
        val id: String, val title: String, val content: String, val url: String,
        val structure: ConfluenceMarkup.Structure, val version: Int
    )
    data class PageSource(val id: String, val title: String, val version: Int, val storage: String)
}
