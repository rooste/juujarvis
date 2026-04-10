package com.juujarvis.service

import com.juujarvis.model.*
import org.springframework.stereotype.Service

@Service
class UserService {

    private val users = mutableMapOf<String, User>()

    init {
        val dad = User(
            id = "dad",
            name = "Dad",
            type = UserType.DAD,
            contacts = listOf(
                ContactInterface(ChannelType.WEB_UI, "web-session"),
                ContactInterface(ChannelType.IMESSAGE, "roose@iki.fi")
            )
        )
        val mom = User(
            id = "mom",
            name = "Mom",
            type = UserType.MOM,
            contacts = listOf(
                ContactInterface(ChannelType.IMESSAGE, "saija.taavettila@iki.fi")
            )
        )
        users[dad.id] = dad
        users[mom.id] = mom
    }

    fun getUser(id: String): User? = users[id]

    fun getAllUsers(): List<User> = users.values.toList()

    fun addUser(user: User): User {
        users[user.id] = user
        return user
    }

    fun findByHandle(address: String): User? =
        users.values.find { user ->
            user.contacts.any { it.address.equals(address, ignoreCase = true) }
        }
}
