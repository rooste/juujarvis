package com.juujarvis.service

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class PersonProfile(
    val personId: String,
    val profile: String,
    val updatedAt: Instant
)

data class ConversationTurn(
    val id: Long,
    val conversationId: String,
    val role: String,
    val content: String,
    val senderName: String?,
    val timestamp: Instant,
    val channel: String
)

data class DailySummary(
    val id: Long,
    val summaryDate: LocalDate,
    val summary: String,
    val followUps: String?,
    val createdAt: Instant
)

@Component
class ConversationStore(
    @Value("\${juujarvis.db-path:juujarvis.db}")
    private val dbPath: String
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbcUrl get() = "jdbc:sqlite:$dbPath"

    @PostConstruct
    fun initSchema() {
        connection().use { conn ->
            conn.createStatement().use { s ->
                s.execute("PRAGMA busy_timeout = 5000")
                s.execute("""
                    CREATE TABLE IF NOT EXISTS conversation_turn (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        conversation_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        sender_name TEXT,
                        timestamp TEXT NOT NULL,
                        channel TEXT NOT NULL
                    )
                """.trimIndent())
                s.execute("CREATE INDEX IF NOT EXISTS idx_turn_conv_ts ON conversation_turn(conversation_id, timestamp)")
                s.execute("CREATE INDEX IF NOT EXISTS idx_turn_ts ON conversation_turn(timestamp)")
                s.execute("""
                    CREATE TABLE IF NOT EXISTS daily_summary (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        summary_date TEXT NOT NULL UNIQUE,
                        summary TEXT NOT NULL,
                        follow_ups TEXT,
                        created_at TEXT NOT NULL
                    )
                """.trimIndent())
                s.execute("""
                    CREATE TABLE IF NOT EXISTS person_profile (
                        person_id TEXT PRIMARY KEY,
                        profile TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                """.trimIndent())
                s.execute("""
                    CREATE TABLE IF NOT EXISTS family_user (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                """.trimIndent())
                s.execute("""
                    CREATE TABLE IF NOT EXISTS user_contact (
                        user_id TEXT NOT NULL,
                        channel_type TEXT NOT NULL,
                        address TEXT NOT NULL,
                        PRIMARY KEY (user_id, channel_type, address),
                        FOREIGN KEY (user_id) REFERENCES family_user(id)
                    )
                """.trimIndent())
            }
        }
        log.info("Conversation store initialized at {}", dbPath)
    }

    fun saveTurn(conversationId: String, role: String, content: String, senderName: String?, channel: String, timestamp: Instant) {
        connection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO conversation_turn (conversation_id, role, content, sender_name, timestamp, channel) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, conversationId)
                stmt.setString(2, role)
                stmt.setString(3, content)
                stmt.setString(4, senderName)
                stmt.setString(5, timestamp.toString())
                stmt.setString(6, channel)
                stmt.executeUpdate()
            }
        }
    }

    fun loadRecentTurns(conversationId: String, limit: Int = 20): List<ConversationTurn> {
        return connection().use { conn ->
            conn.prepareStatement("""
                SELECT * FROM (
                    SELECT id, conversation_id, role, content, sender_name, timestamp, channel
                    FROM conversation_turn
                    WHERE conversation_id = ?
                    ORDER BY timestamp DESC
                    LIMIT ?
                ) sub ORDER BY timestamp ASC
            """.trimIndent()).use { stmt ->
                stmt.setString(1, conversationId)
                stmt.setInt(2, limit)
                val rs = stmt.executeQuery()
                val turns = mutableListOf<ConversationTurn>()
                while (rs.next()) {
                    turns += ConversationTurn(
                        id = rs.getLong("id"),
                        conversationId = rs.getString("conversation_id"),
                        role = rs.getString("role"),
                        content = rs.getString("content"),
                        senderName = rs.getString("sender_name"),
                        timestamp = Instant.parse(rs.getString("timestamp")),
                        channel = rs.getString("channel")
                    )
                }
                turns
            }
        }
    }

    fun loadTurnsSince(since: Instant): List<ConversationTurn> {
        return connection().use { conn ->
            conn.prepareStatement(
                "SELECT id, conversation_id, role, content, sender_name, timestamp, channel FROM conversation_turn WHERE timestamp >= ? ORDER BY timestamp ASC"
            ).use { stmt ->
                stmt.setString(1, since.toString())
                val rs = stmt.executeQuery()
                val turns = mutableListOf<ConversationTurn>()
                while (rs.next()) {
                    turns += ConversationTurn(
                        id = rs.getLong("id"),
                        conversationId = rs.getString("conversation_id"),
                        role = rs.getString("role"),
                        content = rs.getString("content"),
                        senderName = rs.getString("sender_name"),
                        timestamp = Instant.parse(rs.getString("timestamp")),
                        channel = rs.getString("channel")
                    )
                }
                turns
            }
        }
    }

    fun saveDailySummary(date: LocalDate, summary: String, followUps: String?) {
        connection().use { conn ->
            conn.prepareStatement(
                "INSERT OR IGNORE INTO daily_summary (summary_date, summary, follow_ups, created_at) VALUES (?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, date.toString())
                stmt.setString(2, summary)
                stmt.setString(3, followUps)
                stmt.setString(4, Instant.now().toString())
                stmt.executeUpdate()
            }
        }
    }

    fun loadRecentSummaries(days: Int = 7): List<DailySummary> {
        val since = LocalDate.now(ZoneId.systemDefault()).minusDays(days.toLong())
        return connection().use { conn ->
            conn.prepareStatement(
                "SELECT id, summary_date, summary, follow_ups, created_at FROM daily_summary WHERE summary_date >= ? ORDER BY summary_date ASC"
            ).use { stmt ->
                stmt.setString(1, since.toString())
                val rs = stmt.executeQuery()
                val summaries = mutableListOf<DailySummary>()
                while (rs.next()) {
                    summaries += DailySummary(
                        id = rs.getLong("id"),
                        summaryDate = LocalDate.parse(rs.getString("summary_date")),
                        summary = rs.getString("summary"),
                        followUps = rs.getString("follow_ups"),
                        createdAt = Instant.parse(rs.getString("created_at"))
                    )
                }
                summaries
            }
        }
    }

    fun savePersonProfile(personId: String, profile: String) {
        connection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO person_profile (person_id, profile, updated_at) VALUES (?, ?, ?) ON CONFLICT(person_id) DO UPDATE SET profile = ?, updated_at = ?"
            ).use { stmt ->
                val now = Instant.now().toString()
                stmt.setString(1, personId)
                stmt.setString(2, profile)
                stmt.setString(3, now)
                stmt.setString(4, profile)
                stmt.setString(5, now)
                stmt.executeUpdate()
            }
        }
    }

    fun loadPersonProfile(personId: String): PersonProfile? {
        return connection().use { conn ->
            conn.prepareStatement(
                "SELECT person_id, profile, updated_at FROM person_profile WHERE person_id = ?"
            ).use { stmt ->
                stmt.setString(1, personId)
                val rs = stmt.executeQuery()
                if (rs.next()) PersonProfile(
                    personId = rs.getString("person_id"),
                    profile = rs.getString("profile"),
                    updatedAt = Instant.parse(rs.getString("updated_at"))
                ) else null
            }
        }
    }

    fun loadAllProfiles(): List<PersonProfile> {
        return connection().use { conn ->
            conn.createStatement().use { s ->
                val rs = s.executeQuery("SELECT person_id, profile, updated_at FROM person_profile ORDER BY person_id")
                val profiles = mutableListOf<PersonProfile>()
                while (rs.next()) {
                    profiles += PersonProfile(
                        personId = rs.getString("person_id"),
                        profile = rs.getString("profile"),
                        updatedAt = Instant.parse(rs.getString("updated_at"))
                    )
                }
                profiles
            }
        }
    }

    // ── User persistence ──

    fun saveUser(user: com.juujarvis.model.User) {
        connection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO family_user (id, name, type, created_at) VALUES (?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET name = ?, type = ?"
            ).use { stmt ->
                val now = Instant.now().toString()
                stmt.setString(1, user.id)
                stmt.setString(2, user.name)
                stmt.setString(3, user.type.name)
                stmt.setString(4, now)
                stmt.setString(5, user.name)
                stmt.setString(6, user.type.name)
                stmt.executeUpdate()
            }
            // Replace contacts
            conn.prepareStatement("DELETE FROM user_contact WHERE user_id = ?").use { stmt ->
                stmt.setString(1, user.id)
                stmt.executeUpdate()
            }
            conn.prepareStatement("INSERT INTO user_contact (user_id, channel_type, address) VALUES (?, ?, ?)").use { stmt ->
                user.contacts.forEach { contact ->
                    stmt.setString(1, user.id)
                    stmt.setString(2, contact.channelType.name)
                    stmt.setString(3, contact.address)
                    stmt.executeUpdate()
                }
            }
        }
    }

    fun loadAllUsers(): List<com.juujarvis.model.User> {
        return connection().use { conn ->
            val users = mutableListOf<com.juujarvis.model.User>()
            conn.createStatement().use { s ->
                val rs = s.executeQuery("SELECT id, name, type FROM family_user ORDER BY name")
                while (rs.next()) {
                    val userId = rs.getString("id")
                    val contacts = loadContactsForUser(conn, userId)
                    users += com.juujarvis.model.User(
                        id = userId,
                        name = rs.getString("name"),
                        type = com.juujarvis.model.UserType.valueOf(rs.getString("type")),
                        contacts = contacts
                    )
                }
            }
            users
        }
    }

    fun deleteUser(userId: String) {
        connection().use { conn ->
            conn.prepareStatement("DELETE FROM user_contact WHERE user_id = ?").use { it.setString(1, userId); it.executeUpdate() }
            conn.prepareStatement("DELETE FROM family_user WHERE id = ?").use { it.setString(1, userId); it.executeUpdate() }
        }
    }

    private fun loadContactsForUser(conn: java.sql.Connection, userId: String): List<com.juujarvis.model.ContactInterface> {
        return conn.prepareStatement("SELECT channel_type, address FROM user_contact WHERE user_id = ?").use { stmt ->
            stmt.setString(1, userId)
            val rs = stmt.executeQuery()
            val contacts = mutableListOf<com.juujarvis.model.ContactInterface>()
            while (rs.next()) {
                contacts += com.juujarvis.model.ContactInterface(
                    channelType = com.juujarvis.model.ChannelType.valueOf(rs.getString("channel_type")),
                    address = rs.getString("address")
                )
            }
            contacts
        }
    }

    private fun connection() = DriverManager.getConnection(jdbcUrl).also { conn ->
        conn.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
    }
}
