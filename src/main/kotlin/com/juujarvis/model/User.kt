package com.juujarvis.model

import java.util.UUID

data class User(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: UserType,
    val contacts: List<ContactInterface> = emptyList()
)
