package com.juujarvis.tool

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolPolicyTest {
    private fun allowed(tool: String, action: String? = null) =
        ToolPolicy.allowedInUntrustedTurn(tool, if (action != null) mapOf("action" to action) else emptyMap())

    @Test
    fun `untrusted turns only get read-only actions`() {
        assertTrue(allowed("manage_calendar", "list"))
        assertTrue(allowed("manage_calendar", "search"))
        assertFalse(allowed("manage_calendar", "create"))
        assertFalse(allowed("manage_calendar", "delete"))
        assertFalse(allowed("manage_calendar"))                 // missing action is not a read
        assertTrue(allowed("manage_warranty", "view"))
        assertFalse(allowed("manage_warranty", "save"))
        assertTrue(allowed("read_messages"))
        assertFalse(allowed("send_message"))
        assertFalse(allowed("web_search"))                      // outbound query could leak data
        assertFalse(allowed("manage_user", "add"))
        assertFalse(allowed("record_forest_transaction"))
        assertFalse(allowed("update_person_profile"))
        assertFalse(allowed("email", "reply_all"))
        assertFalse(allowed("unknown_tool"))
        assertFalse(ToolPolicy.availableInUntrustedTurn("send_message"))
        assertTrue(ToolPolicy.availableInUntrustedTurn("manage_calendar"))
    }

    @Test
    fun `school turns get the school workflow and nothing more`() {
        fun school(tool: String, action: String? = null) =
            ToolPolicy.allowed(ToolProfile.SCHOOL, tool, if (action != null) mapOf("action" to action) else emptyMap())
        assertTrue(school("manage_calendar", "create"))
        assertTrue(school("manage_calendar", "update"))
        assertFalse(school("manage_calendar", "delete"))
        assertTrue(school("manage_task", "create"))
        assertTrue(school("manage_task", "complete"))
        assertTrue(school("send_message"))
        assertTrue(school("read_messages"))
        assertFalse(school("web_search"))
        assertFalse(school("email", "send"))
        assertFalse(school("email", "reply_all"))
        assertFalse(school("manage_user", "update"))
        assertFalse(school("update_person_profile"))
        assertFalse(school("record_forest_transaction"))
        assertFalse(school("manage_warranty", "delete"))
        // read-only email turns are unchanged: no calendar writes, no messages
        assertFalse(ToolPolicy.allowed(ToolProfile.READ_ONLY, "manage_calendar", mapOf("action" to "create")))
        assertFalse(ToolPolicy.allowed(ToolProfile.READ_ONLY, "send_message", emptyMap()))
        assertTrue(ToolPolicy.allowed(ToolProfile.FULL, "web_search", emptyMap()))
        // announcements are judged by the model (family requests, school emergencies), in every profile
        assertTrue(school("announce_on_echo"))
        assertTrue(ToolPolicy.allowed(ToolProfile.READ_ONLY, "announce_on_echo", emptyMap()))
    }

    @Test
    fun `voice turns get everyday tools but nothing reaching outside the family`() {
        fun voice(tool: String, action: String? = null) =
            ToolPolicy.allowed(ToolProfile.VOICE, tool, if (action != null) mapOf("action" to action) else emptyMap())
        assertTrue(voice("manage_calendar", "create"))
        assertTrue(voice("manage_calendar", "delete"))
        assertTrue(voice("manage_task", "create"))
        assertTrue(voice("send_message"))
        assertTrue(voice("web_search"))
        assertTrue(voice("email", "read_inbox"))
        assertTrue(voice("alexa_device", "set"))
        assertTrue(voice("manage_user", "list"))
        assertFalse(voice("email", "send"))
        assertFalse(voice("email", "reply_all"))
        assertFalse(voice("manage_user", "update"))
        assertFalse(voice("manage_user", "remove"))
        assertFalse(voice("update_person_profile"))
        assertFalse(voice("record_forest_transaction"))
        assertFalse(voice("manage_warranty", "delete"))
        assertTrue(voice("announce_on_echo"))
        // Confluence: everyone can read; only trusted family chats can write
        assertTrue(voice("search_confluence", "get_page"))
        assertFalse(voice("search_confluence", "create_page"))
        assertFalse(voice("search_confluence", "update_page"))
        assertFalse(ToolPolicy.allowed(ToolProfile.SMS, "search_confluence", mapOf("action" to "update_page")))
        assertFalse(allowed("search_confluence", "create_page"))
        assertTrue(allowed("search_confluence", "search"))
        assertFalse(ToolPolicy.allowed(ToolProfile.SCHOOL, "search_confluence", mapOf("action" to "update_page")))
        assertTrue(ToolPolicy.allowed(ToolProfile.FULL, "search_confluence", mapOf("action" to "create_page")))
        // texts get the same everyday tools as voice, with their own refusal text
        assertTrue(ToolPolicy.allowed(ToolProfile.SMS, "manage_calendar", mapOf("action" to "create")))
        assertFalse(ToolPolicy.allowed(ToolProfile.SMS, "email", mapOf("action" to "send")))
        assertFalse(ToolPolicy.allowed(ToolProfile.SMS, "update_person_profile", emptyMap()))
        assertTrue(ToolPolicy.denied(ToolProfile.SMS).startsWith("Not available by text message"))
        assertTrue(ToolPolicy.denied(ToolProfile.VOICE).startsWith("Not available by voice"))
        assertTrue(ToolPolicy.denied(ToolProfile.READ_ONLY).contains("email"))
    }
}
