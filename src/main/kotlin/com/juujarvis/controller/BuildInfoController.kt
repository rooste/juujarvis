package com.juujarvis.controller

import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class BuildInfoController {

    @GetMapping("/api/version", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun version(): ResponseEntity<String> {
        val resource = ClassPathResource("build-info.json")
        return if (resource.exists()) {
            ResponseEntity.ok(resource.inputStream.bufferedReader().readText())
        } else {
            ResponseEntity.ok("""{"buildTime":"unknown","gitHash":"unknown","version":"unknown"}""")
        }
    }
}
