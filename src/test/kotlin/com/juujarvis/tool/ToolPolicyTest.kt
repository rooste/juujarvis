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
}
