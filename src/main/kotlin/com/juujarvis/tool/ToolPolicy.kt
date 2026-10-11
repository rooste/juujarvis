package com.juujarvis.tool

/**
 * Which tools and actions may run in an untrusted turn (one triggered by email content). Only reads
 * are allowed: anything that changes state, sends something, or reaches outside (like web search,
 * which could leak data through its query) needs the user's confirmation over iMessage instead.
 */
object ToolPolicy {
    /** Tool name → allowed actions, or null when every use of the tool is read-only. */
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
    )

    fun availableInUntrustedTurn(toolName: String) = toolName in readOnly

    fun allowedInUntrustedTurn(toolName: String, arguments: Map<String, Any?>): Boolean {
        if (toolName !in readOnly) return false
        val actions = readOnly[toolName] ?: return true
        return (arguments["action"] as? String) in actions
    }

    const val DENIED =
        "Not allowed: this turn was triggered by an email, so only read-only actions are available. " +
        "Describe what you would do and ask the sender to confirm by iMessage; do it after they confirm there."
}
