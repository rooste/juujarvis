package com.juujarvis.model

import java.time.Instant

data class ImageAttachment(
    val filePath: String,
    val mediaType: String,
    val filename: String
)

data class IncomingMessage(
    val userId: String,
    val channel: ChannelType,
    val text: String,
    val timestamp: Instant = Instant.now(),
    val conversation: Conversation? = null,
    val images: List<ImageAttachment> = emptyList(),
    /** Content came from outside the family's trusted channels (e.g. email): read-only tools only. */
    val untrusted: Boolean = false
)

data class OutgoingMessage(
    val userId: String,
    val channel: ChannelType,
    val text: String,
    val timestamp: Instant = Instant.now()
)
