package com.juujarvis.messaging.imessage

import com.juujarvis.model.ChannelType
import com.juujarvis.model.Conversation
import com.juujarvis.model.IncomingMessage
import com.juujarvis.service.MessageRouter
import com.juujarvis.service.WebSocketService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty("juujarvis.imessage.polling-enabled", havingValue = "true", matchIfMissing = true)
class IMessagePoller(
    private val messageRouter: MessageRouter,
    private val webSocketService: WebSocketService
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val dbPath = System.getProperty("user.home") + "/Library/Messages/chat.db"
    private val appleEpochOffset = 978307200L

    private val lastSeenRowId = AtomicLong(currentMaxRowId())

    @Scheduled(fixedDelay = 3000)
    fun poll() {
        val since = lastSeenRowId.get()
        val newMessages = fetchSince(since)
        if (newMessages.isEmpty()) return

        log.info("iMessage poller: {} new message(s) since rowid {}", newMessages.size, since)
        newMessages.forEach { (rowId, msg) ->
            lastSeenRowId.updateAndGet { maxOf(it, rowId) }
            webSocketService.broadcastIMessage("in", msg.userId, msg.text)
            messageRouter.handleIncoming(msg)
        }
    }

    private fun fetchSince(sinceRowId: Long): List<Pair<Long, IncomingMessage>> {
        return try {
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                conn.prepareStatement(QUERY).use { stmt ->
                    stmt.setLong(1, sinceRowId)
                    val rs = stmt.executeQuery()
                    val results = mutableListOf<Pair<Long, IncomingMessage>>()
                    while (rs.next()) {
                        val text = rs.getString("text")
                            ?: rs.getBytes("attributedBody")
                                ?.let { extractText(it) }
                            ?: continue
                        if (text.isBlank()) continue

                        val rowId = rs.getLong("rowid")
                        val handle = rs.getString("handle_id") ?: "unknown"
                        val appleNanos = rs.getLong("date")
                        val timestamp = Instant.ofEpochSecond(appleEpochOffset + appleNanos / 1_000_000_000L)

                        val chatIdentifier = rs.getString("chat_identifier")
                        val chatGuid = rs.getString("chat_guid")
                        val style = rs.getInt("style")
                        val displayName = rs.getString("display_name")

                        val conversation = if (chatIdentifier != null && chatGuid != null) {
                            Conversation(
                                chatId = chatIdentifier,
                                chatGuid = chatGuid,
                                isGroup = (style == 43),
                                displayName = displayName,
                                participants = fetchChatParticipants(conn, chatIdentifier)
                            )
                        } else null

                        results += rowId to IncomingMessage(
                            userId = handle,
                            channel = ChannelType.IMESSAGE,
                            text = text,
                            timestamp = timestamp,
                            conversation = conversation
                        )
                    }
                    results
                }
            }
        } catch (e: Exception) {
            log.error("Failed to poll iMessage database: {}", e.message)
            emptyList()
        }
    }

    private fun fetchChatParticipants(conn: Connection, chatIdentifier: String): List<String> {
        conn.prepareStatement(PARTICIPANTS_QUERY).use { stmt ->
            stmt.setString(1, chatIdentifier)
            val rs = stmt.executeQuery()
            val handles = mutableListOf<String>()
            while (rs.next()) handles += rs.getString("id")
            return handles
        }
    }

    private fun currentMaxRowId(): Long {
        return try {
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                conn.createStatement().use { s ->
                    val rs = s.executeQuery("SELECT COALESCE(MAX(rowid), 0) FROM message")
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        } catch (e: Exception) {
            log.warn("Could not read max rowid on startup, defaulting to 0: {}", e.message)
            0L
        }
    }

    private fun extractText(bytes: ByteArray): String? {
        val marker = "NSString".toByteArray(Charsets.US_ASCII)
        var markerIdx = -1
        outer@ for (i in 0..bytes.size - marker.size) {
            for (j in marker.indices) { if (bytes[i + j] != marker[j]) continue@outer }
            markerIdx = i; break
        }
        if (markerIdx < 0) return null
        for (i in (markerIdx + marker.size) until bytes.size - 1) {
            if (bytes[i] == 0x2b.toByte()) {
                val len = bytes[i + 1].toInt() and 0xFF
                if (len > 0 && i + 2 + len <= bytes.size)
                    return String(bytes, i + 2, len, Charsets.UTF_8)
            }
        }
        return null
    }

    companion object {
        private val QUERY = """
            SELECT m.rowid, m.text, m.date, m.attributedBody,
                   COALESCE(h.id, 'unknown') AS handle_id,
                   c.chat_identifier, c.guid AS chat_guid, c.display_name, c.style
            FROM message m
            LEFT JOIN handle h ON m.handle_id = h.rowid
            LEFT JOIN chat_message_join cmj ON cmj.message_id = m.rowid
            LEFT JOIN chat c ON c.rowid = cmj.chat_id
            WHERE m.rowid > ?
              AND m.is_from_me = 0
            ORDER BY m.rowid ASC
        """.trimIndent()

        private val PARTICIPANTS_QUERY = """
            SELECT h.id FROM handle h
            JOIN chat_handle_join chj ON chj.handle_id = h.rowid
            JOIN chat c ON c.rowid = chj.chat_id
            WHERE c.chat_identifier = ?
        """.trimIndent()
    }
}
