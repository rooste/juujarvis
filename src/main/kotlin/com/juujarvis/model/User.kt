package com.juujarvis.model

import java.time.ZoneId
import java.util.UUID

data class User(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: UserType,
    val contacts: List<ContactInterface> = emptyList(),
    val timezone: ZoneId = ZoneId.of("America/Chicago")
)
