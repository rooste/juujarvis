package com.juujarvis.bridgarvis

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

data class BridgarvisSendRequest(val to: String?, val text: String?)

/** Diagnostics for the Bridgarvis connection (local web API only). */
@RestController
@RequestMapping("/api/bridgarvis")
@ConditionalOnProperty("juujarvis.bridgarvis.enabled", havingValue = "true")
class BridgarvisController(private val client: BridgarvisClient) {

    @GetMapping("/status")
    fun status() = client.status()

    @PostMapping("/send")
    fun send(@RequestBody request: BridgarvisSendRequest): ResponseEntity<Any> {
        val to = request.to?.takeIf { it.isNotBlank() }
        val text = request.text?.takeIf { it.isNotBlank() }
        if (to == null || text == null) return ResponseEntity.badRequest().body(mapOf("error" to "'to' and 'text' are required"))
        return ResponseEntity.ok(client.send(to, text))
    }
}
