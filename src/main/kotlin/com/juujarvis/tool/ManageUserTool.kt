package com.juujarvis.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.juujarvis.model.*
import com.juujarvis.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ManageUserTool(private val userService: UserService) : JuujarvisTool {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "manage_user"

    override fun definition(): Tool {
        return Tool.builder()
            .name(name)
            .description(
                "Add, update, or remove a family member. Use this when someone asks to add a new person, " +
                "update someone's contact info, or remove a user. You can also list all current users."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        JsonValue.from(
                            mapOf(
                                "action" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("add", "update", "remove", "list"),
                                    "description" to "Action to perform"
                                ),
                                "name" to mapOf(
                                    "type" to "string",
                                    "description" to "Person's name (e.g., 'Samuli', 'Grandma')"
                                ),
                                "type" to mapOf(
                                    "type" to "string",
                                    "enum" to UserType.entries.map { it.name },
                                    "description" to "Relationship type: DAD, MOM, CHILD, GRANDPARENT, or FRIEND"
                                ),
                                "imessage" to mapOf(
                                    "type" to "string",
                                    "description" to "iMessage address (email or phone number)"
                                ),
                                "email" to mapOf(
                                    "type" to "string",
                                    "description" to "Email address"
                                ),
                                "phone" to mapOf(
                                    "type" to "string",
                                    "description" to "Phone number for SMS"
                                )
                            )
                        )
                    )
                    .required(JsonValue.from(listOf("action")))
                    .build()
            )
            .build()
    }

    @Suppress("UNCHECKED_CAST")
    override fun execute(arguments: Map<String, Any?>): String {
        val action = arguments["action"] as? String ?: return "Error: action is required"

        return when (action) {
            "list" -> {
                val users = userService.getAllUsers()
                if (users.isEmpty()) return "No users registered"
                users.joinToString("\n") { u ->
                    val contacts = u.contacts.joinToString(", ") { "${it.channelType}: ${it.address}" }
                    "- ${u.name} (${u.type}) — $contacts"
                }
            }

            "add" -> {
                val personName = arguments["name"] as? String ?: return "Error: name is required"
                val exactMatch = userService.findByName(personName)
                if (exactMatch != null) {
                    return "'${exactMatch.name}' already exists (${exactMatch.type}). Use the 'update' action to modify their info."
                }

                // Check for similar names — ask the user to confirm before adding
                val similarUsers = findSimilarUsers(personName)
                if (similarUsers.isNotEmpty()) {
                    val matches = similarUsers.joinToString(", ") { "'${it.name}' (${it.type})" }
                    return "SIMILAR USER(S) FOUND: $matches. " +
                        "Before adding '$personName', ask the user: is this a new person, or should you update " +
                        "the existing user's contact info instead? If it's the same person, use 'update' with the existing name."
                }

                val typeName = arguments["type"] as? String
                val userType = if (typeName != null) {
                    try { UserType.valueOf(typeName) } catch (e: Exception) { return "Error: invalid type '$typeName'" }
                } else {
                    UserType.FRIEND
                }

                val contacts = buildContacts(arguments)
                val userId = personName.lowercase().replace(" ", "-")
                val user = User(id = userId, name = personName, type = userType, contacts = contacts)
                userService.addUser(user)
                log.info("Added user: {} ({}) with {} contacts", personName, userType, contacts.size)

                val contactSummary = contacts.joinToString(", ") { "${it.channelType}: ${it.address}" }
                "Added ${user.name} (${user.type})" + if (contactSummary.isNotBlank()) " — $contactSummary" else ""
            }

            "update" -> {
                val personName = arguments["name"] as? String ?: return "Error: name is required"
                val existing = userService.findByName(personName)
                    ?: return "No user found with name '$personName'. Use the 'add' action to create them first."

                val typeName = arguments["type"] as? String
                val userType = if (typeName != null) {
                    try { UserType.valueOf(typeName) } catch (e: Exception) { return "Error: invalid type '$typeName'" }
                } else {
                    existing.type
                }

                val contacts = mutableListOf<ContactInterface>()
                contacts.addAll(existing.contacts)
                mergeContacts(contacts, arguments)

                val user = User(id = existing.id, name = personName, type = userType, contacts = contacts)
                userService.addUser(user)
                log.info("Updated user: {} ({}) with {} contacts", personName, userType, contacts.size)

                val contactSummary = contacts.joinToString(", ") { "${it.channelType}: ${it.address}" }
                "Updated ${user.name} (${user.type}) — $contactSummary"
            }

            "remove" -> {
                val personName = arguments["name"] as? String ?: return "Error: name is required"
                val user = userService.findByName(personName)
                    ?: return "No user found with name '$personName'"
                userService.removeUser(user.id)
                "Removed ${user.name} from the family list"
            }

            else -> "Unknown action: $action. Use add, update, remove, or list."
        }
    }

    private fun findSimilarUsers(name: String): List<User> {
        val nameLower = name.lowercase()
        return userService.getAllUsers().filter { user ->
            val existing = user.name.lowercase()
            // Check: one name contains the other, or they share a first/last name part
            existing != nameLower && (
                existing.contains(nameLower) || nameLower.contains(existing) ||
                nameParts(existing).any { part -> nameParts(nameLower).any { it == part && it.length >= 3 } }
            )
        }
    }

    private fun nameParts(name: String): List<String> = name.split(" ", "-").filter { it.length >= 3 }

    private fun buildContacts(arguments: Map<String, Any?>): List<ContactInterface> {
        val contacts = mutableListOf<ContactInterface>()
        (arguments["imessage"] as? String)?.let { contacts.add(ContactInterface(ChannelType.IMESSAGE, it)) }
        (arguments["email"] as? String)?.let { contacts.add(ContactInterface(ChannelType.EMAIL, it)) }
        (arguments["phone"] as? String)?.let { contacts.add(ContactInterface(ChannelType.SMS, it)) }
        return contacts
    }

    private fun mergeContacts(contacts: MutableList<ContactInterface>, arguments: Map<String, Any?>) {
        (arguments["imessage"] as? String)?.let { addr ->
            contacts.removeAll { it.channelType == ChannelType.IMESSAGE }
            contacts.add(ContactInterface(ChannelType.IMESSAGE, addr))
        }
        (arguments["email"] as? String)?.let { addr ->
            contacts.removeAll { it.channelType == ChannelType.EMAIL }
            contacts.add(ContactInterface(ChannelType.EMAIL, addr))
        }
        (arguments["phone"] as? String)?.let { addr ->
            contacts.removeAll { it.channelType == ChannelType.SMS }
            contacts.add(ContactInterface(ChannelType.SMS, addr))
        }
    }
}
