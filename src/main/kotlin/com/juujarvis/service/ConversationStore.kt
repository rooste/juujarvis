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

data class GroupChatInfo(
    val conversationId: String,
    val chatGuid: String,
    val displayName: String?,
    val members: List<String> = emptyList()
)

data class ScheduledReminder(
    val id: Long,
    val recipient: String,
    val recipientType: String,
    val message: String,
    val sendAt: Instant
)

data class JuujarvisTask(
    val id: Long = 0,
    val title: String,
    val description: String? = null,
    val assignedTo: String? = null,
    val status: String = "open",
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null
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
                        timezone TEXT,
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
                s.execute("""
                    CREATE TABLE IF NOT EXISTS scheduled_reminder (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        recipient TEXT NOT NULL,
                        recipient_type TEXT NOT NULL DEFAULT 'user',
                        message TEXT NOT NULL,
                        send_at TEXT NOT NULL,
                        sent INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL
                    )
                """.trimIndent())
                s.execute("CREATE INDEX IF NOT EXISTS idx_reminder_send ON scheduled_reminder(send_at, sent)")
                s.execute("""
                    CREATE TABLE IF NOT EXISTS juujarvis_task (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        title TEXT NOT NULL,
                        description TEXT,
                        assigned_to TEXT,
                        status TEXT NOT NULL DEFAULT 'open',
                        created_at TEXT NOT NULL,
                        completed_at TEXT
                    )
                """.trimIndent())
                s.execute("CREATE INDEX IF NOT EXISTS idx_task_status ON juujarvis_task(status)")

                // Migrate: add timezone column to existing databases
                try {
                    s.execute("ALTER TABLE family_user ADD COLUMN timezone TEXT")
                } catch (_: Exception) {
                    // Column already exists — ignore
                }
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
                "INSERT INTO family_user (id, name, type, timezone, created_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET name = ?, type = ?, timezone = ?"
            ).use { stmt ->
                val now = Instant.now().toString()
                stmt.setString(1, user.id)
                stmt.setString(2, user.name)
                stmt.setString(3, user.type.name)
                stmt.setString(4, user.timezone.id)
                stmt.setString(5, now)
                stmt.setString(6, user.name)
                stmt.setString(7, user.type.name)
                stmt.setString(8, user.timezone.id)
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
                val rs = s.executeQuery("SELECT id, name, type, timezone FROM family_user ORDER BY name")
                while (rs.next()) {
                    val userId = rs.getString("id")
                    val contacts = loadContactsForUser(conn, userId)
                    val tz = rs.getString("timezone")
                    users += com.juujarvis.model.User(
                        id = userId,
                        name = rs.getString("name"),
                        type = com.juujarvis.model.UserType.valueOf(rs.getString("type")),
                        contacts = contacts,
                        timezone = if (tz != null) ZoneId.of(tz) else ZoneId.systemDefault()
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

    // ── Group chat discovery ──

    fun loadRecentGroupChats(days: Int = 7): List<GroupChatInfo> {
        val since = java.time.LocalDate.now(ZoneId.systemDefault()).minusDays(days.toLong())
            .atStartOfDay(ZoneId.systemDefault()).toInstant()
        return connection().use { conn ->
            // Find group conversation IDs
            val groupIds = mutableListOf<String>()
            conn.prepareStatement("""
                SELECT DISTINCT conversation_id FROM conversation_turn
                WHERE timestamp >= ? AND conversation_id NOT LIKE 'web-ui-%'
            """.trimIndent()).use { stmt ->
                stmt.setString(1, since.toString())
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val convId = rs.getString("conversation_id")
                    if (convId.startsWith("chat") || convId.contains(";+;")) {
                        groupIds += convId
                    }
                }
            }

            // For each group, find unique members (excluding Juujarvis)
            groupIds.map { convId ->
                val members = mutableListOf<String>()
                conn.prepareStatement("""
                    SELECT DISTINCT sender_name FROM conversation_turn
                    WHERE conversation_id = ? AND sender_name IS NOT NULL AND sender_name != 'Juujarvis'
                    ORDER BY sender_name
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, convId)
                    val rs = stmt.executeQuery()
                    while (rs.next()) {
                        members += rs.getString("sender_name")
                    }
                }
                GroupChatInfo(
                    conversationId = convId,
                    chatGuid = convId,
                    displayName = null,
                    members = members
                )
            }
        }
    }

    // ── Scheduled reminders ──

    fun saveReminder(recipient: String, message: String, sendAt: Instant, recipientType: String = "user") {
        connection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO scheduled_reminder (recipient, recipient_type, message, send_at, sent, created_at) VALUES (?, ?, ?, ?, 0, ?)"
            ).use { stmt ->
                stmt.setString(1, recipient)
                stmt.setString(2, recipientType)
                stmt.setString(3, message)
                stmt.setString(4, sendAt.toString())
                stmt.setString(5, Instant.now().toString())
                stmt.executeUpdate()
            }
        }
    }

    fun loadDueReminders(): List<ScheduledReminder> {
        val now = Instant.now().toString()
        return connection().use { conn ->
            conn.prepareStatement(
                "SELECT id, recipient, recipient_type, message, send_at FROM scheduled_reminder WHERE sent = 0 AND send_at <= ? ORDER BY send_at ASC"
            ).use { stmt ->
                stmt.setString(1, now)
                val rs = stmt.executeQuery()
                val reminders = mutableListOf<ScheduledReminder>()
                while (rs.next()) {
                    reminders += ScheduledReminder(
                        id = rs.getLong("id"),
                        recipient = rs.getString("recipient"),
                        recipientType = rs.getString("recipient_type"),
                        message = rs.getString("message"),
                        sendAt = Instant.parse(rs.getString("send_at"))
                    )
                }
                reminders
            }
        }
    }

    fun markReminderSent(id: Long) {
        connection().use { conn ->
            conn.prepareStatement("UPDATE scheduled_reminder SET sent = 1 WHERE id = ?").use { stmt ->
                stmt.setLong(1, id)
                stmt.executeUpdate()
            }
        }
    }

    // ── Tasks ──

    fun saveTask(task: JuujarvisTask): Long {
        return connection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO juujarvis_task (title, description, assigned_to, status, created_at, completed_at) VALUES (?, ?, ?, ?, ?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS
            ).use { stmt ->
                stmt.setString(1, task.title)
                stmt.setString(2, task.description)
                stmt.setString(3, task.assignedTo)
                stmt.setString(4, task.status)
                stmt.setString(5, task.createdAt.toString())
                stmt.setString(6, task.completedAt?.toString())
                stmt.executeUpdate()
                val rs = stmt.generatedKeys
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    fun loadTasksByStatus(status: String): List<JuujarvisTask> {
        return connection().use { conn ->
            conn.prepareStatement(
                "SELECT id, title, description, assigned_to, status, created_at, completed_at FROM juujarvis_task WHERE status = ? ORDER BY created_at ASC"
            ).use { stmt ->
                stmt.setString(1, status)
                val rs = stmt.executeQuery()
                val tasks = mutableListOf<JuujarvisTask>()
                while (rs.next()) {
                    tasks += readTask(rs)
                }
                tasks
            }
        }
    }

    fun loadAllTasks(): List<JuujarvisTask> {
        return connection().use { conn ->
            conn.createStatement().use { s ->
                val rs = s.executeQuery("SELECT id, title, description, assigned_to, status, created_at, completed_at FROM juujarvis_task ORDER BY created_at ASC")
                val tasks = mutableListOf<JuujarvisTask>()
                while (rs.next()) {
                    tasks += readTask(rs)
                }
                tasks
            }
        }
    }

    fun completeTask(id: Long) {
        connection().use { conn ->
            conn.prepareStatement(
                "UPDATE juujarvis_task SET status = 'completed', completed_at = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, Instant.now().toString())
                stmt.setLong(2, id)
                stmt.executeUpdate()
            }
        }
    }

    fun updateTask(id: Long, title: String?, description: String?, assignedTo: String?, status: String?) {
        connection().use { conn ->
            val sets = mutableListOf<String>()
            val values = mutableListOf<Any?>()
            title?.let { sets += "title = ?"; values += it }
            description?.let { sets += "description = ?"; values += it }
            assignedTo?.let { sets += "assigned_to = ?"; values += it }
            status?.let {
                sets += "status = ?"
                values += it
                if (it == "completed") {
                    sets += "completed_at = ?"
                    values += Instant.now().toString()
                }
            }
            if (sets.isEmpty()) return
            conn.prepareStatement("UPDATE juujarvis_task SET ${sets.joinToString(", ")} WHERE id = ?").use { stmt ->
                values.forEachIndexed { i, v -> stmt.setString(i + 1, v as String) }
                stmt.setLong(values.size + 1, id)
                stmt.executeUpdate()
            }
        }
    }

    private fun readTask(rs: java.sql.ResultSet): JuujarvisTask {
        val completedStr = rs.getString("completed_at")
        return JuujarvisTask(
            id = rs.getLong("id"),
            title = rs.getString("title"),
            description = rs.getString("description"),
            assignedTo = rs.getString("assigned_to"),
            status = rs.getString("status"),
            createdAt = Instant.parse(rs.getString("created_at")),
            completedAt = if (completedStr != null) Instant.parse(completedStr) else null
        )
    }

    private fun connection() = DriverManager.getConnection(jdbcUrl).also { conn ->
        conn.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
    }
}
