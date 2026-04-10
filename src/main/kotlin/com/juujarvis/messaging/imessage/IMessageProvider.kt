package com.juujarvis.messaging.imessage

import com.juujarvis.messaging.ChatMessage
import com.juujarvis.messaging.MessagingProvider
import com.juujarvis.model.ChannelType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.DriverManager
import java.time.Instant

/**
 * MessagingProvider backed by the macOS iMessage database (~/Library/Messages/chat.db).
 * Sending uses osascript (AppleScript).
 *
 * Prerequisites:
 *  - The JVM process (or Terminal/IDE launching it) must have Full Disk Access granted in
 *    System Settings > Privacy & Security > Full Disk Access.
 *  - Messages app must be signed into an Apple ID for sending to work.
 */
@Component
class IMessageProvider : MessagingProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override val channelType = ChannelType.IMESSAGE

    private val dbPath: String = System.getProperty("user.home") + "/Library/Messages/chat.db"

    // Apple's reference epoch starts 2001-01-01; convert to Unix epoch (seconds).
    private val appleEpochOffset = 978307200L

    override fun send(to: String, message: String): Boolean {
        val safeMessage = message.replace("\\", "\\\\").replace("\"", "\\\"")
        val script = """
            tell application "Messages"
                set targetService to 1st service whose service type = iMessage
                set targetBuddy to buddy "$to" of targetService
                send "$safeMessage" to targetBuddy
            end tell
        """.trimIndent()

        return try {
            val result = ProcessBuilder("osascript", "-e", script)
                .redirectErrorStream(true)
                .start()
                .waitFor()
            result == 0
        } catch (e: Exception) {
            log.error("Failed to send iMessage to {}: {}", to, e.message)
            false
        }
    }

    override fun getMessages(withContact: String?, limit: Int): List<ChatMessage> {
        return try {
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                val sql = if (withContact != null) QUERY_WITH_CONTACT else QUERY_ALL
                conn.prepareStatement(sql).use { stmt ->
                    if (withContact != null) {
                        stmt.setString(1, withContact)
                        stmt.setInt(2, limit)
                    } else {
                        stmt.setInt(1, limit)
                    }
                    val rs = stmt.executeQuery()
                    val messages = mutableListOf<ChatMessage>()
                    while (rs.next()) {
                        val text = rs.getString("text")
                            ?: rs.getBytes("attributedBody")?.let { extractTextFromAttributedBody(it) }
                            ?: continue
                        if (text.isBlank()) continue

                        val isFromMe = rs.getInt("is_from_me") == 1
                        val appleNanos = rs.getLong("date")
                        val timestamp = Instant.ofEpochSecond(appleEpochOffset + appleNanos / 1_000_000_000L)
                        val handle = rs.getString("handle_id") ?: "unknown"

                        messages += ChatMessage(
                            text = text,
                            from = if (isFromMe) "me" else handle,
                            to = if (isFromMe) handle else "me",
                            isFromMe = isFromMe,
                            timestamp = timestamp,
                            channel = ChannelType.IMESSAGE
                        )
                    }
                    messages
                }
            }
        } catch (e: Exception) {
            log.error("Failed to read iMessage database: {}", e.message, e)
            emptyList()
        }
    }

    /**
     * The attributedBody blob uses Apple's old NSArchiver "streamtyped" format.
     * The plain text string follows a 0x2b ('+') byte + 1-byte length after the "NSString" marker.
     */
    private fun extractTextFromAttributedBody(bytes: ByteArray): String? {
        val marker = "NSString".toByteArray(Charsets.US_ASCII)
        val markerIdx = bytes.indexOfSequence(marker)
        if (markerIdx < 0) return null

        for (i in (markerIdx + marker.size) until bytes.size - 1) {
            if (bytes[i] == 0x2b.toByte()) {
                val len = bytes[i + 1].toInt() and 0xFF
                if (len > 0 && i + 2 + len <= bytes.size) {
                    return String(bytes, i + 2, len, Charsets.UTF_8)
                }
            }
        }
        return null
    }

    private fun ByteArray.indexOfSequence(seq: ByteArray): Int {
        outer@ for (i in 0..this.size - seq.size) {
            for (j in seq.indices) {
                if (this[i + j] != seq[j]) continue@outer
            }
            return i
        }
        return -1
    }

    companion object {
        private val QUERY_ALL = """
            SELECT m.text, m.is_from_me, m.date, m.attributedBody, COALESCE(h.id, 'unknown') AS handle_id
            FROM message m
            LEFT JOIN handle h ON m.handle_id = h.rowid
            ORDER BY m.date DESC
            LIMIT ?
        """.trimIndent()

        private val QUERY_WITH_CONTACT = """
            SELECT m.text, m.is_from_me, m.date, m.attributedBody, COALESCE(h.id, 'unknown') AS handle_id
            FROM message m
            LEFT JOIN handle h ON m.handle_id = h.rowid
            WHERE h.id = ?
            ORDER BY m.date DESC
            LIMIT ?
        """.trimIndent()
    }
}
