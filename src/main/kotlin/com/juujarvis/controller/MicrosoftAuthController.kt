package com.juujarvis.controller

import com.juujarvis.service.MicrosoftAuthService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.view.RedirectView

@RestController
@RequestMapping("/auth/microsoft")
class MicrosoftAuthController(
    private val authService: MicrosoftAuthService
) {

    @GetMapping("/login")
    fun login(): RedirectView {
        return RedirectView(authService.buildAuthorizationUrl())
    }

    @GetMapping("/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) error: String?,
        @RequestParam(name = "error_description", required = false) errorDescription: String?
    ): ResponseEntity<String> {
        if (error != null) {
            return ResponseEntity.badRequest().body("Microsoft auth error: $error — $errorDescription")
        }
        if (code == null) {
            return ResponseEntity.badRequest().body("No authorization code received from Microsoft.")
        }
        val success = authService.exchangeCodeForTokens(code)
        return if (success) {
            ResponseEntity.ok("Microsoft account connected successfully! You can close this window.")
        } else {
            ResponseEntity.internalServerError().body("Failed to connect Microsoft account. Check the logs.")
        }
    }

    @GetMapping("/status")
    fun status(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "configured" to authService.isConfigured(),
            "authenticated" to authService.hasTokens()
        ))
    }
}
