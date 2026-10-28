package com.juujarvis.messaging

import com.juujarvis.messaging.sms.PhoneNumbers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneNumbersTest {
    @Test
    fun `phone numbers match however they are written`() {
        assertEquals("+12145550123", PhoneNumbers.normalize("+1 (214) 555-0123"))
        assertEquals("+12145550123", PhoneNumbers.normalize("214.555.0123"))
        assertEquals("+12145550123", PhoneNumbers.normalize("12145550123"))
        assertNull(PhoneNumbers.normalize("alex@example.com"))
        assertNull(PhoneNumbers.normalize("+44 20 7946 0958"))
    }
}
