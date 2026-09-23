package com.vinnovateit.latch.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CredentialPromptTest {
    @Test
    fun `a lowercase registration number is normalized before being accepted`() {
        val terminal = FakeTerminal(line = "22bce0001", secret = "secret".toCharArray())

        val result = promptForCredentials(terminal)

        assertTrue(result.isSuccess)
        assertEquals("22BCE0001", result.getOrThrow().userId)
    }

    @Test
    fun `a malformed registration number is rejected before a password is even asked for`() {
        val terminal = FakeTerminal(line = "not-a-reg-no", secret = "secret".toCharArray())

        val result = promptForCredentials(terminal)

        assertTrue(result.isFailure)
        assertEquals("Invalid registration number.", result.exceptionOrNull()?.message)
        assertEquals(0, terminal.secretPrompts)
    }

    @Test
    fun `a blank user id is rejected with its own message`() {
        val terminal = FakeTerminal(line = "", secret = "secret".toCharArray())

        val result = promptForCredentials(terminal)

        assertTrue(result.isFailure)
        assertEquals("A user ID is required.", result.exceptionOrNull()?.message)
    }
}

private class FakeTerminal(
    private val line: String?,
    private val secret: CharArray?,
) : TerminalIO {
    var secretPrompts = 0
    override val interactive: Boolean = true
    override fun print(text: String) = Unit
    override fun println(text: String) = Unit
    override fun readLine(prompt: String): String? = line
    override fun readSecret(prompt: String): CharArray? {
        secretPrompts++
        return secret
    }
}
