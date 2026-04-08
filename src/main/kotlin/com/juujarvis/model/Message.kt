package com.juujarvis.model

import java.time.Instant

data class IncomingMessage(
    val userId: String,
    val channel: ChannelType,
    val text: String,
    val timestamp: Instant = Instant.now()
)

data class OutgoingMessage(
    val userId: String,
    val channel: ChannelType,
    val text: String,
    val timestamp: Instant = Instant.now()
)
