package com.juujarvis.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class OneDriveExcelService(
    private val authService: MicrosoftAuthService
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()
    private val restClient = RestClient.builder()
        .baseUrl("https://graph.microsoft.com/v1.0")
        .build()

    /**
     * Find a shared Excel file by name.
     * Returns the drive item ID or null.
     */
    fun findSharedFile(fileName: String): DriveItemInfo? {
        val token = authService.getAccessToken() ?: return null

        return try {
            val response = restClient.get()
                .uri("/me/drive/sharedWithMe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)
            val items = json.get("value") ?: return null

            for (item in items) {
                val name = item.get("name")?.asText() ?: continue
                if (name.equals(fileName, ignoreCase = true)) {
                    val remoteItem = item.get("remoteItem")
                    val itemId = remoteItem?.get("id")?.asText() ?: item.get("id")?.asText() ?: continue
                    val driveId = remoteItem?.get("parentReference")?.get("driveId")?.asText()
                    return DriveItemInfo(itemId, driveId)
                }
            }
            null
        } catch (e: Exception) {
            log.error("Failed to find shared file '{}': {}", fileName, e.message)
            null
        }
    }

    /**
     * Add a row to a named table in an Excel workbook.
     */
    fun addTableRow(item: DriveItemInfo, tableName: String, values: List<Any?>): Boolean {
        val token = authService.getAccessToken() ?: return false

        val basePath = if (item.driveId != null) {
            "/drives/${item.driveId}/items/${item.itemId}"
        } else {
            "/me/drive/items/${item.itemId}"
        }

        val payload = objectMapper.createObjectNode().apply {
            putArray("values").addArray().apply {
                values.forEach { v ->
                    when (v) {
                        is Number -> add(v.toDouble())
                        is String -> add(v)
                        null -> addNull()
                        else -> add(v.toString())
                    }
                }
            }
        }

        return try {
            restClient.post()
                .uri("$basePath/workbook/tables/$tableName/rows/add")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .retrieve()
                .toBodilessEntity()

            log.info("Added row to table '{}': {}", tableName, values)
            true
        } catch (e: Exception) {
            log.error("Failed to add row to table '{}': {}", tableName, e.message)
            false
        }
    }

    data class DriveItemInfo(
        val itemId: String,
        val driveId: String?
    )
}
