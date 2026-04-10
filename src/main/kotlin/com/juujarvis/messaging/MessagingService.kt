package com.juujarvis.messaging

import com.juujarvis.model.ChannelType
import com.juujarvis.model.ContactInterface
import com.juujarvis.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MessagingService(
    providers: List<MessagingProvider>,
    private val userService: UserService
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

    fun availableChannels(): Set<ChannelType> = providerMap.keys

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
