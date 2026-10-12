package com.juujarvis.model

import com.juujarvis.tool.ToolProfile

import java.time.Instant

data class ImageAttachment(
    val filePath: String,
    val mediaType: String,
    val filename: String
)

/** How to answer an email in its thread: reply goes to [to] with the threading headers set. */
data class EmailReplyContext(
    val to: String,
    val subject: String,
    val inReplyTo: String?,
    val references: String?
)

/**
 * A spoken request through an Echo (via Bridgarvis). The answer goes back to Alexa as speech; if that is
 * too late, it is sent by iMessage to [fallbackHandle].
 */
data class AlexaContext(
    val requestId: String,
    val deviceId: String,
    val room: String?,
    val defaultPerson: String?,
    val personId: String?,
    val fallbackHandle: String?
)

data class IncomingMessage(
    val userId: String,
    val channel: ChannelType,
    val text: String,
    val timestamp: Instant = Instant.now(),
    val conversation: Conversation? = null,
    val images: List<ImageAttachment> = emptyList(),
    /** Which tools this turn may use; anything but FULL means the content came from outside. */
    val toolProfile: ToolProfile = ToolProfile.FULL,
    /** Set for email turns that should be answered by email (in the same thread) instead of iMessage. */
    val emailReply: EmailReplyContext? = null,
    /** Set for Alexa turns: the answer is spoken on the Echo the question came from. */
    val alexa: AlexaContext? = null
) {
    /** Content came from outside the family's trusted channels (email): wrapped and tool-limited. */
    val untrusted: Boolean get() = toolProfile != ToolProfile.FULL
}

data class OutgoingMessage(
    val userId: String,
    val channel: ChannelType,
    val text: String,
    val timestamp: Instant = Instant.now()
)
