package com.juujarvis.model

data class ContactInterface(
    val channelType: ChannelType,
    val address: String // phone number, email, or session id
)
