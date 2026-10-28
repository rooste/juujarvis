package com.juujarvis.bridgarvis

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.ssl.SslBundles
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Outcome of an email send: [detail] is the Bridgarvis message id on success, the reason otherwise. */
data class EmailSendResult(val ok: Boolean, val detail: String)

sealed interface SmsSendResult {
    data class Sent(val messageId: String) : SmsSendResult
    /** [code]: opted_out, not_opted_in, rate_limited, invalid_number, send_failed, unreachable, ... */
    data class Refused(val code: String, val detail: String) : SmsSendResult {
        /** The number itself can't be texted (as opposed to a temporary failure). */
        val numberBlocked: Boolean get() = code == "opted_out" || code == "not_opted_in" || code == "invalid_number"
    }
}

enum class AlexaReplyResult { SPOKEN, QUEUED, FAILED }

data class BridgarvisStatus(
    val url: String,
    val connected: Boolean,
    val connectedSince: Instant?,
    val reconnectAttempts: Int,
    val notificationsReceived: Int,
    val lastNotificationAt: Instant?,
    val lastFetchedId: String?,
    val lastFetchedFrom: String?,
    val lastError: String?
)

/**
 * Mutual-TLS client for Bridgarvis (the AWS SMS gateway).
 *
 * Keeps the notification WebSocket open (reconnecting with backoff, pinging to keep it alive).
 * Bridgarvis announces messages as {"type":"message.available","id":"..","channel":".."}; the message itself is
 * fetched over REST. On every (re)connect all pending messages are drained as well.
 *
 * Fetched messages go to [BridgarvisInbox]; once handled they are acked, which removes them from
 * Bridgarvis's pending list. Unhandled ones (e.g. SMS, not routed yet) stay pending.
 */
@Component
@ConditionalOnProperty("juujarvis.bridgarvis.enabled", havingValue = "true")
class BridgarvisClient(
    sslBundles: SslBundles,
    private val mapper: ObjectMapper,
    private val inbox: BridgarvisInbox,
    @Value("\${juujarvis.bridgarvis.url}") private val baseUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val http = HttpClient.newBuilder()
        .sslContext(sslBundles.getBundle("bridgarvis").createSslContext())
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "bridgarvis").apply { isDaemon = true } }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "bridgarvis-fetch").apply { isDaemon = true } }

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connectedSince: Instant? = null
    @Volatile private var lastNotificationAt: Instant? = null
    @Volatile private var lastFetchedId: String? = null
    @Volatile private var lastFetchedFrom: String? = null
    @Volatile private var lastError: String? = null
    @Volatile private var stopped = false
    private val attempts = AtomicInteger(0)
    private val notifications = AtomicInteger(0)
    private var pinger: ScheduledFuture<*>? = null

    @PostConstruct
    fun start() {
        log.info("Bridgarvis client enabled, connecting to {}", baseUrl)
        scheduler.execute { connect() }
        pinger = scheduler.scheduleAtFixedRate({ socket?.sendPing(ByteBuffer.allocate(0)) }, 30, 30, TimeUnit.SECONDS)
    }

    @PreDestroy
    fun stop() {
        stopped = true
        pinger?.cancel(false)
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
        scheduler.shutdownNow()
        worker.shutdownNow()
    }

    fun status() = BridgarvisStatus(
        url = baseUrl,
        connected = socket != null,
        connectedSince = connectedSince,
        reconnectAttempts = attempts.get(),
        notificationsReceived = notifications.get(),
        lastNotificationAt = lastNotificationAt,
        lastFetchedId = lastFetchedId,
        lastFetchedFrom = lastFetchedFrom,
        lastError = lastError
    )

    /**
     * Sends an email through Bridgarvis (SES, from the assistant's address). [EmailSendResult.ok] is true
     * when SES accepted it; otherwise [EmailSendResult.detail] says why (also logged).
     */
    fun sendEmail(to: List<String>, subject: String, text: String, inReplyTo: String? = null, references: String? = null): EmailSendResult {
        val body = mapper.writeValueAsString(mapOf(
            "to" to to, "subject" to subject, "text" to text, "inReplyTo" to inReplyTo, "references" to references
        ))
        return try {
            val response = http.send(
                HttpRequest.newBuilder(uri("/api/email/send"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (response.statusCode() == 200) {
                val id = mapper.readTree(response.body())["id"]?.asText() ?: ""
                log.info("Email sent via Bridgarvis: {}", id)
                EmailSendResult(true, id)
            } else {
                log.warn("Bridgarvis email send failed: HTTP {} {}", response.statusCode(), response.body().take(300))
                EmailSendResult(false, "HTTP ${response.statusCode()} ${response.body().take(200)}".trim())
            }
        } catch (e: Exception) {
            log.warn("Bridgarvis email send failed: {}", e.message)
            EmailSendResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Answers an Alexa question. SPOKEN: Alexa says it now. QUEUED: too late for the request; Bridgarvis
     * keeps it for the asking Echo. FAILED: unknown/expired or the call failed; use another channel.
     */
    fun replyAlexa(id: String, speech: String, endSession: Boolean, reprompt: String? = null, room: String? = null): AlexaReplyResult {
        val body = mapper.writeValueAsString(mapOf("speech" to speech, "endSession" to endSession, "reprompt" to reprompt, "room" to room))
        return try {
            val response = http.send(
                HttpRequest.newBuilder(uri("/api/alexa/$id/reply"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (response.statusCode() != 200) {
                log.warn("Bridgarvis Alexa reply failed: HTTP {}", response.statusCode())
                return AlexaReplyResult.FAILED
            }
            val result = mapper.readTree(response.body())
            when {
                result["delivered"]?.asBoolean() == true -> AlexaReplyResult.SPOKEN
                result["queued"]?.asBoolean() == true -> AlexaReplyResult.QUEUED
                else -> AlexaReplyResult.FAILED
            }
        } catch (e: Exception) {
            log.warn("Bridgarvis Alexa reply failed: {}", e.message)
            AlexaReplyResult.FAILED
        }
    }

    /**
     * Asks Bridgarvis to speak [text] on an Echo. Returns null if it could not be queued, otherwise whether
     * the Echo's sensor was opened (the routine plays it now) or it waits until the skill is opened there.
     */
    fun announceAlexa(deviceId: String, text: String): Boolean? {
        val body = mapper.writeValueAsString(mapOf("deviceId" to deviceId, "text" to text))
        return try {
            val response = http.send(
                HttpRequest.newBuilder(uri("/api/alexa/announce"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (response.statusCode() != 200) {
                log.warn("Bridgarvis Alexa announcement failed: HTTP {}", response.statusCode())
                return null
            }
            mapper.readTree(response.body())["announced"]?.asBoolean() == true
        } catch (e: Exception) {
            log.warn("Bridgarvis Alexa announcement failed: {}", e.message)
            null
        }
    }

    /**
     * Sends an SMS through Bridgarvis. A refusal carries Bridgarvis's code: opted_out / not_opted_in mean
     * this number must not be texted; anything else is a temporary or delivery problem.
     */
    fun sendSms(to: String, text: String): SmsSendResult = try {
        val response = http.send(
            HttpRequest.newBuilder(uri("/api/messages/send"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("to" to to, "text" to text))))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        val body = runCatching { mapper.readTree(response.body()) }.getOrNull()
        if (response.statusCode() == 200) SmsSendResult.Sent(body?.get("messageId")?.asText() ?: "")
        else SmsSendResult.Refused(body?.get("error")?.asText() ?: "http_${response.statusCode()}", body?.get("detail")?.asText() ?: "")
    } catch (e: Exception) {
        log.warn("Bridgarvis SMS send failed: {}", e.message)
        SmsSendResult.Refused("unreachable", e.message ?: "")
    }

    /** Bridgarvis's consent records (number → OPTED_IN / OPTED_OUT), or null if unavailable. */
    fun smsConsent(): Map<String, String>? = try {
        val response = http.send(
            HttpRequest.newBuilder(uri("/api/sms/consent")).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (response.statusCode() != 200) null
        else mapper.readTree(response.body()).associate { it["number"].asText() to it["consent"].asText() }
    } catch (e: Exception) {
        log.warn("Bridgarvis SMS consent fetch failed: {}", e.message)
        null
    }

    private fun connect() {
        if (stopped) return
        val wsUri = URI.create(baseUrl.replaceFirst("https://", "wss://").trimEnd('/') + "/ws")
        http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(wsUri, Listener())
            .whenComplete { ws, error ->
                if (error != null) {
                    onDisconnected("connect failed: ${error.cause?.message ?: error.message}")
                } else {
                    socket = ws
                    connectedSince = Instant.now()
                    attempts.set(0)
                    lastError = null
                    log.info("Bridgarvis WebSocket connected")
                    worker.execute { drainPending() }
                }
            }
    }

    private fun onDisconnected(reason: String) {
        socket = null
        connectedSince = null
        lastError = reason
        if (stopped) return
        val attempt = attempts.incrementAndGet()
        val delay = minOf(60L, 1L shl minOf(attempt - 1, 6))
        log.warn("Bridgarvis disconnected ({}); reconnecting in {}s", reason, delay)
        scheduler.schedule({ connect() }, delay, TimeUnit.SECONDS)
    }

    private fun drainPending() {
        try {
            val response = http.send(HttpRequest.newBuilder(uri("/api/messages")).GET().build(), HttpResponse.BodyHandlers.ofString())
            val pending = mapper.readTree(response.body())
            log.info("Bridgarvis has {} pending message(s)", pending.size())
            pending.forEach { process(it) }
        } catch (e: Exception) {
            lastError = "drain failed: ${e.message}"
            log.warn("Bridgarvis drain failed: {}", e.message)
        }
    }

    private fun fetch(id: String) {
        try {
            val response = http.send(HttpRequest.newBuilder(uri("/api/messages/$id")).GET().build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) process(mapper.readTree(response.body()))
            else log.warn("Bridgarvis message {} fetch returned HTTP {}", id, response.statusCode())
        } catch (e: Exception) {
            lastError = "fetch failed: ${e.message}"
            log.warn("Bridgarvis fetch of {} failed: {}", id, e.message)
        }
    }

    private fun process(message: JsonNode) {
        record(message)
        val id = message["id"]?.asText() ?: return
        val handled = try {
            inbox.handle(message)
        } catch (e: Exception) {
            log.error("Handling Bridgarvis message {} failed", id, e)
            false
        }
        if (handled) ack(id)
    }

    private fun ack(id: String) {
        try {
            val response = http.send(
                HttpRequest.newBuilder(uri("/api/messages/$id/ack")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()
            )
            log.info("Bridgarvis message {} acked (HTTP {})", id, response.statusCode())
        } catch (e: Exception) {
            lastError = "ack failed: ${e.message}"
            log.warn("Bridgarvis ack of {} failed: {}", id, e.message)
        }
    }

    private fun record(message: JsonNode) {
        lastFetchedId = message["id"]?.asText()
        lastFetchedFrom = message["from"]?.asText()
        log.info("Bridgarvis message {} fetched", lastFetchedId)
    }

    private fun uri(path: String) = URI.create(baseUrl.trimEnd('/') + path)

    private inner class Listener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val frame = runCatching { mapper.readTree(buffer.toString()) }.getOrNull()
                buffer.setLength(0)
                if (frame != null && frame.get("type")?.asText() in setOf("message.available", "sms.available")) {
                    notifications.incrementAndGet()
                    lastNotificationAt = Instant.now()
                    frame.get("id")?.asText()?.let { id -> worker.execute { fetch(id) } }
                }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            onDisconnected("closed $statusCode ${reason.ifBlank { "" }}".trim())
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            onDisconnected("error: ${error.message}")
        }
    }
}
