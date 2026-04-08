package com.juujarvis.service

import com.juujarvis.model.*
import org.springframework.stereotype.Service

@Service
class UserService {

    private val users = mutableMapOf<String, User>()

    init {
        // Bootstrap dad as the default web UI user for POC
        val dad = User(
            id = "dad",
            name = "Dad",
            type = UserType.DAD,
            contacts = listOf(
                ContactInterface(ChannelType.WEB_UI, "web-session")
            )
        )
        users[dad.id] = dad
    }

    fun getUser(id: String): User? = users[id]

    fun getAllUsers(): List<User> = users.values.toList()

    fun addUser(user: User): User {
        users[user.id] = user
        return user
    }
}
