package com.juujarvis.tool

/** What a turn may do with tools, based on where its content came from. */
enum class ToolProfile {
    /** Family member on a trusted channel (iMessage, web UI): every tool. */
    FULL,
    /** Email to the assistant: reads only; changes need confirmation over iMessage. */
    READ_ONLY,
    /** Authenticated school mail: reads plus the school workflow (calendar, tasks, iMessages to family). */
    SCHOOL,
    /**
     * Spoken request through an Echo: someone in the household, but not a verified person. Everyday tools;
     * nothing that reaches outside the family or changes users, profiles or finances.
     */
    VOICE
}

/**
 * Which tools and actions a turn may use. Content from email is untrusted, so those turns only get
 * what their purpose needs. Web search stays off for them (a query could leak data), and nothing can
 * delete, change users/profiles, record finances or send email.
 */
object ToolPolicy {
    /** Tool name → allowed actions, or null when every use of the tool is allowed. */
    private val readOnly: Map<String, Set<String>?> = mapOf(
        "manage_calendar" to setOf("list", "search"),
        "email" to setOf("read_inbox", "read_message"),
        "manage_ideas" to setOf("backlog", "list", "read"),
        "manage_task" to setOf("list"),
        "manage_user" to setOf("list"),
        "manage_warranty" to setOf("search", "list", "view"),
        "search_confluence" to null,
        "read_messages" to null,
        "zoom_image" to null,
        "announce_on_echo" to null,  // judgement rules in its description; rate limited
    )

    private val school: Map<String, Set<String>?> = readOnly + mapOf(
        "manage_calendar" to setOf("list", "search", "create", "update"),
        "manage_task" to setOf("list", "create", "update", "complete"),
        "send_message" to null,      // only reaches family members and family group chats
    )

    private val voice: Map<String, Set<String>?> = mapOf(
        "manage_calendar" to null,
        "email" to setOf("read_inbox", "read_message"),
        "manage_ideas" to null,
        "manage_task" to null,
        "manage_user" to setOf("list"),
        "manage_warranty" to setOf("search", "list", "view"),
        "search_confluence" to null,
        "read_messages" to null,
        "send_message" to null,      // only reaches family members and family group chats
        "web_search" to null,
        "alexa_device" to null,
        "announce_on_echo" to null,
    )

    private fun rules(profile: ToolProfile): Map<String, Set<String>?>? = when (profile) {
        ToolProfile.FULL -> null
        ToolProfile.READ_ONLY -> readOnly
        ToolProfile.SCHOOL -> school
        ToolProfile.VOICE -> voice
    }

    fun available(profile: ToolProfile, toolName: String): Boolean = rules(profile)?.containsKey(toolName) ?: true

    fun allowed(profile: ToolProfile, toolName: String, arguments: Map<String, Any?>): Boolean {
        val rules = rules(profile) ?: return true
        if (toolName !in rules) return false
        val actions = rules[toolName] ?: return true
        return (arguments["action"] as? String) in actions
    }

    // Kept for the read-only email turns (and their tests)
    fun availableInUntrustedTurn(toolName: String) = available(ToolProfile.READ_ONLY, toolName)
    fun allowedInUntrustedTurn(toolName: String, arguments: Map<String, Any?>) = allowed(ToolProfile.READ_ONLY, toolName, arguments)

    const val DENIED =
        "Not allowed: this turn was triggered by an email, so this action is not available. " +
        "Describe what you would do and ask Dad (or the sender) to confirm by iMessage; do it after they confirm there."

    const val VOICE_DENIED =
        "Not available by voice: anyone near the Echo could be asking. Say so briefly and offer to do it " +
        "when they ask by iMessage."

    fun denied(profile: ToolProfile) = if (profile == ToolProfile.VOICE) VOICE_DENIED else DENIED
}
