package com.juujarvis.messaging

import com.juujarvis.model.ChannelType
import com.juujarvis.model.ContactInterface
import com.juujarvis.model.Conversation
import com.juujarvis.service.ConversationStore
import com.juujarvis.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MessagingService(
    providers: List<MessagingProvider>,
    private val userService: UserService,
    private val conversationStore: ConversationStore
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val providerMap: Map<ChannelType, MessagingProvider> = providers.associateBy { it.channelType }

    init {
        log.info("Registered messaging providers: {}", providerMap.keys)
    }

    /**
     * Send a message to a named user, using their preferred available channel.
     * Returns a human-readable result string.
     */
    fun sendToUser(recipientName: String, message: String): String {
        val user = userService.getAllUsers().find { it.name.equals(recipientName, ignoreCase = true) }
            ?: return "No user found with name '$recipientName'"

        val contact = user.contacts
            .firstOrNull { providerMap.containsKey(it.channelType) }
            ?: return "No supported messaging channel found for ${user.name}"

        return send(contact, message, user.name)
    }

    /**
     * Send a message via a specific contact interface.
     */
    fun send(contact: ContactInterface, message: String, displayName: String = contact.address): String {
        val provider = providerMap[contact.channelType]
            ?: return "No provider available for channel ${contact.channelType}"

        log.info("Sending {} message to {} ({})", contact.channelType, displayName, contact.address)
        val success = provider.send(contact.address, message)
        return if (success) "Message sent to $displayName via ${contact.channelType}"
               else "Failed to send message to $displayName via ${contact.channelType}"
    }

    /**
     * Get recent messages from a provider, optionally filtered to a contact.
     */
    fun getMessages(channel: ChannelType, withContact: String? = null, limit: Int = 50): List<ChatMessage> {
        val provider = providerMap[channel]
            ?: throw IllegalArgumentException("No provider for channel $channel")
        return provider.getMessages(withContact, limit)
    }

    /**
     * Send a message to a group chat identified by member names.
     * Discovers groups directly from iMessage's chat.db by matching
     * participant handles to the requested member names.
     */
    fun sendToGroup(memberNames: List<String>, message: String): String {
        val provider = providerMap[ChannelType.IMESSAGE]
            ?: return "No iMessage provider available for group messaging"

        // Resolve requested names to all known addresses (email, phone, etc.)
        val memberHandleSets = memberNames.mapNotNull { name ->
            val user = userService.getAllUsers().find { it.name.equals(name, ignoreCase = true) }
            user?.contacts?.map { it.address.lowercase() }?.toSet()
        }

        if (memberHandleSets.isEmpty()) {
            return "Could not find contacts for: ${memberNames.joinToString(", ")}"
        }

        // Find matching group chat directly from chat.db
        val match = findGroupChatByMembers(memberHandleSets)
            ?: return "No group chat found containing: ${memberNames.joinToString(", ")}"

        log.info("Sending to group (guid={}, members={})", match.first, memberNames)
        val success = provider.sendToChat(match.first, message)
        return if (success) "Message sent to group (${memberNames.joinToString(", ")})"
               else "Failed to send message to group (${memberNames.joinToString(", ")})"
    }

    /**
     * Find a group chat in chat.db where each requested member has at least one
     * handle present in the group's participant list.
     * memberHandleSets: for each requested member, the set of all their known addresses.
     * Returns Pair(guid, chat_identifier) or null.
     */
    private fun findGroupChatByMembers(memberHandleSets: List<Set<String>>): Pair<String, String>? {
        val dbPath = System.getProperty("user.home") + "/Library/Messages/chat.db"
        return try {
            java.sql.DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                val groups = mutableListOf<Triple<String, String, Set<String>>>() // guid, identifier, member handles
                conn.createStatement().use { s ->
                    val rs = s.executeQuery("SELECT ROWID, guid, chat_identifier FROM chat WHERE style = 43")
                    while (rs.next()) {
                        val chatRowId = rs.getLong("ROWID")
                        val guid = rs.getString("guid")
                        val identifier = rs.getString("chat_identifier")
                        val members = mutableSetOf<String>()
                        conn.prepareStatement(
                            "SELECT h.id FROM handle h JOIN chat_handle_join chj ON chj.handle_id = h.ROWID WHERE chj.chat_id = ?"
                        ).use { stmt ->
                            stmt.setLong(1, chatRowId)
                            val mrs = stmt.executeQuery()
                            while (mrs.next()) {
                                members += mrs.getString("id").lowercase()
                            }
                        }
                        groups += Triple(guid, identifier, members)
                    }
                }

                // Find a group where every requested member has at least one handle in the group
                groups.find { (_, _, groupMembers) ->
                    memberHandleSets.all { memberHandles ->
                        memberHandles.any { it in groupMembers }
                    }
                }?.let { (guid, identifier, _) -> guid to identifier }
            }
        } catch (e: Exception) {
            log.error("Failed to search group chats in chat.db: {}", e.message)
            null
        }
    }

    fun availableChannels(): Set<ChannelType> = providerMap.keys

    /**
     * Send a message to a conversation (group or 1-on-1).
     * Groups use sendToChat; 1-on-1 sends to the first participant.
     */
    fun sendToConversation(conversation: Conversation, message: String): Boolean {
        val provider = providerMap[ChannelType.IMESSAGE] ?: run {
            log.warn("No IMESSAGE provider available")
            return false
        }
        return if (conversation.isGroup) {
            log.info("Sending to group chat {} (guid={})", conversation.chatId, conversation.chatGuid)
            provider.sendToChat(conversation.chatGuid, message)
        } else {
            val address = conversation.participants.firstOrNull() ?: conversation.chatId
            log.info("Sending to 1-on-1 chat with {}", address)
            provider.send(address, message)
        }
    }

    /**
     * Send a message directly to a raw address (phone number, email handle, etc.)
     * on the given channel, bypassing user lookup.
     */
    fun sendDirect(channel: ChannelType, address: String, message: String): Boolean {
        val provider = providerMap[channel] ?: run {
            log.warn("No provider for channel {}", channel)
            return false
        }
        log.info("Sending direct {} message to {}", channel, address)
        return provider.send(address, message)
    }
}
