package com.juujarvis.service

import com.juujarvis.model.ChannelType
import com.juujarvis.model.IncomingMessage
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@Configuration
@ConfigurationProperties(prefix = "juujarvis.email")
class EmailPollerConfig {
    var pollingEnabled: Boolean = false
    var defaultIntervalSeconds: Int = 1200
    var schedules: List<ScheduleEntry> = emptyList()

    class ScheduleEntry {
        var start: String = "00:00"
        var end: String = "23:59"
        var intervalSeconds: Int = 60
    }
}

@Component
@ConditionalOnProperty("juujarvis.email.polling-enabled", havingValue = "true")
class EmailPoller(
    private val outlookEmailService: OutlookEmailService,
    private val conversationStore: ConversationStore,
    private val messageRouter: MessageRouter,
    private val config: EmailPollerConfig
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of("America/Chicago")
    private var lastPollTime = 0L

    @jakarta.annotation.PostConstruct
    fun init() {
        if (outlookEmailService.isAvailable()) {
            log.info("Email poller starting — checking inbox on startup")
            pollInbox()
            lastPollTime = System.currentTimeMillis()
        }
    }

    @Scheduled(fixedDelay = 10_000) // check every 10 seconds whether it's time to poll
    fun tick() {
        if (!outlookEmailService.isAvailable()) return

        val now = System.currentTimeMillis()
        val interval = currentIntervalMs()

        if (now - lastPollTime < interval) return
        lastPollTime = now

        pollInbox()
    }

    private fun pollInbox() {
        log.debug("Polling Outlook inbox")
        val emails = outlookEmailService.readRecentEmails(count = 10)

        var newCount = 0
        for (email in emails) {
            if (conversationStore.hasEmailSummary(email.id)) continue

            // Save summary for context
            val summary = email.preview.take(300).ifBlank { "(no preview available)" }
            conversationStore.saveEmailSummary(
                EmailSummaryRecord(
                    messageId = email.id,
                    fromAddress = email.from,
                    fromName = email.fromName,
                    subject = email.subject,
                    summary = summary,
                    receivedAt = email.receivedAt
                )
            )
            newCount++
            log.info("New email from {} — {}", email.from, email.subject)

            // Read full body and route through assistant
            val detail = outlookEmailService.readEmail(email.id) ?: continue
            val bodyText = stripHtml(detail.body).take(2000)
            val messageText = "Email from: ${email.fromName ?: email.from} <${email.from}>\nSubject: ${email.subject}\n\n$bodyText"

            val incoming = IncomingMessage(
                userId = email.from,
                channel = ChannelType.EMAIL,
                text = messageText,
                timestamp = Instant.now()
            )
            messageRouter.handleIncoming(incoming)
        }

        if (newCount > 0) {
            log.info("Processed {} new email(s)", newCount)
        }
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun currentIntervalMs(): Long {
        val now = LocalTime.now(zone)
        for (schedule in config.schedules) {
            val start = LocalTime.parse(schedule.start)
            val end = LocalTime.parse(schedule.end)
            if (now.isAfter(start) && now.isBefore(end)) {
                return schedule.intervalSeconds * 1000L
            }
        }
        return config.defaultIntervalSeconds * 1000L
    }
}
