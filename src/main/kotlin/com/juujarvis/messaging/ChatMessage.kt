package com.juujarvis.messaging

import com.juujarvis.model.ChannelType
import java.time.Instant

data class ChatMessage(
    val text: String,
    val from: String,
    val to: String,
    val isFromMe: Boolean,
    val timestamp: Instant,
    val channel: ChannelType
)
