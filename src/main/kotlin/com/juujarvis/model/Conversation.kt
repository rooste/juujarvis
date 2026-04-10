package com.juujarvis.model

data class Conversation(
    val chatId: String,
    val chatGuid: String,
    val isGroup: Boolean,
    val displayName: String?,
    val participants: List<String>
)
