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

    /** Sends an SMS through Bridgarvis. Returns the response body (messageId + status). */
    fun send(to: String, text: String): JsonNode {
        val body = mapper.writeValueAsString(mapOf("to" to to, "text" to text))
        val response = http.send(
            HttpRequest.newBuilder(uri("/api/messages/send"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        check(response.statusCode() == 200) { "Bridgarvis send failed: HTTP ${response.statusCode()}" }
        return mapper.readTree(response.body())
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
