package com.juujarvis.service

import com.juujarvis.messaging.MessagingService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Tells the owner when Outlook access breaks, with the fix, instead of failing quietly in the log.
 * At most one message a day, because polling retries every few minutes.
 */
@Component
class MicrosoftAuthAlerts(
    private val messagingService: MessagingService,
    @Value("\${juujarvis.bridgarvis.notify-user:dad}") private val ownerId: String,
    @Value("\${server.port:8080}") private val port: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile private var lastAlert: Instant? = null

    @EventListener
    fun onProblem(problem: MicrosoftAuthProblem) {
        val now = Instant.now()
        if (lastAlert?.let { Duration.between(it, now) < Duration.ofHours(24) } == true) return
        lastAlert = now
        val fix = if (problem.secretProblem)
            "The Azure app's client secret is expired or invalid. Switch the app to a public client (no secret) or create a new secret in the Azure portal."
        else
            "The Outlook sign-in has expired. On the Mac mini, open http://localhost:$port/auth/microsoft/login and sign in to the Juujarvis Outlook account."
        val text = "⚠️ Juujarvis can't access the Outlook mailbox (${problem.reason}).\n$fix"
        val result = messagingService.sendToUser(userName(), text)
        log.warn("Outlook access problem reported to owner: {}", result)
    }

    private fun userName() = ownerId.replaceFirstChar { it.uppercase() }
}
