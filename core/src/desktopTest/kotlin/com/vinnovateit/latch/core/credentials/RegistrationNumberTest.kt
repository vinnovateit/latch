package com.vinnovateit.latch.core.credentials

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistrationNumberTest {
    @Test
    fun `a well-formed registration number is valid`() {
        assertTrue(RegistrationNumber.isValid("22BCE0001"))
    }

    @Test
    fun `normalize trims and uppercases without stripping punctuation`() {
        assertEquals("22BCE0001", RegistrationNumber.normalize("22bce0001"))
        assertEquals("22BCE0001", RegistrationNumber.normalize("  22bce0001  "))
        assertEquals("22-BCE-0001", RegistrationNumber.normalize("22-bce-0001"))
    }

    @Test
    fun `a lowercase input normalizes to a valid registration number`() {
        val normalized = RegistrationNumber.normalize("22bce0001")

        assertEquals("22BCE0001", normalized)
        assertTrue(RegistrationNumber.isValid(normalized))
    }

    @Test
    fun `malformed shapes are rejected`() {
        val invalid = listOf(
            "",
            "22BC0001",
            "22BCE000",
            "22BCE00011",
            "ABBCD0001",
            "22BC10001",
        )

        invalid.forEach { candidate ->
            assertFalse(RegistrationNumber.isValid(candidate), "expected '$candidate' to be invalid")
        }
    }
}
