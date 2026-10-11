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
    }
}
