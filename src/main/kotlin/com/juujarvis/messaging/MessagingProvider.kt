package com.juujarvis.messaging

import com.juujarvis.model.ChannelType

interface MessagingProvider {

    val channelType: ChannelType

    /**
     * Send a message to the given address (phone number, email, handle, etc.).
     * Returns true on success.
     */
    fun send(to: String, message: String): Boolean

    /**
     * Send a message to a chat/conversation by its identifier (e.g. group chat ID).
     * Default implementation falls back to send().
     */
    fun sendToChat(chatId: String, message: String): Boolean = send(chatId, message)

    /**
     * Fetch recent messages, optionally filtered to a specific contact address.
     * Results are ordered newest-first.
     */
    fun getMessages(withContact: String? = null, limit: Int = 50): List<ChatMessage>
}
