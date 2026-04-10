package com.juujarvis.service

import com.juujarvis.model.*
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class UserService(private val conversationStore: ConversationStore) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val users = mutableMapOf<String, User>()

    @PostConstruct
    fun init() {
        val stored = conversationStore.loadAllUsers()
        if (stored.isEmpty()) {
            log.info("No users in DB — seeding defaults")
            val defaults = listOf(
                User(
                    id = "dad", name = "Dad", type = UserType.DAD,
                    contacts = listOf(
                        ContactInterface(ChannelType.WEB_UI, "web-session"),
                        ContactInterface(ChannelType.IMESSAGE, "roose@iki.fi")
                    )
                ),
                User(
                    id = "mom", name = "Mom", type = UserType.MOM,
                    contacts = listOf(
                        ContactInterface(ChannelType.IMESSAGE, "saija.taavettila@iki.fi")
                    )
                )
            )
            defaults.forEach { user ->
                conversationStore.saveUser(user)
                users[user.id] = user
            }
        } else {
            stored.forEach { users[it.id] = it }
            log.info("Loaded {} users from DB", stored.size)
        }
    }

    fun getUser(id: String): User? = users[id]

    fun getAllUsers(): List<User> = users.values.toList()

    fun addUser(user: User): User {
        conversationStore.saveUser(user)
        users[user.id] = user
        log.info("Added/updated user: {} ({})", user.name, user.id)
        return user
    }

    fun removeUser(id: String): Boolean {
        val user = users.remove(id) ?: return false
        conversationStore.deleteUser(id)
        log.info("Removed user: {} ({})", user.name, id)
        return true
    }

    fun findByHandle(address: String): User? =
        users.values.find { user ->
            user.contacts.any { it.address.equals(address, ignoreCase = true) }
        }

    fun findByName(name: String): User? =
        users.values.find { it.name.equals(name, ignoreCase = true) }
}
