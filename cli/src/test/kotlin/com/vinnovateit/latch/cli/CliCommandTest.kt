package com.vinnovateit.latch.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliCommandTest {
    @Test
    fun `no arguments starts the daemon`() {
        assertEquals(ParseResult.Success(CliCommand.Daemon), parseCommand(emptyArray()))
    }

    @Test
    fun `one-shot flags select their command`() {
        val cases = mapOf(
            "--status" to CliCommand.Status,
            "--login" to CliCommand.Login,
            "--logout" to CliCommand.Logout,
            "--history" to CliCommand.History,
            "--set-credentials" to CliCommand.SetCredentials,
            "--settings" to CliCommand.GetSettings,
            "--help" to CliCommand.Help,
            "--version" to CliCommand.Version,
        )

        cases.forEach { (argument, expected) ->
            assertEquals(ParseResult.Success(expected), parseCommand(arrayOf(argument)), argument)
        }
    }

    @Test
    fun `settings parses auto-login values`() {
        assertEquals(
            ParseResult.Success(CliCommand.SetAutoLogin(enabled = true)),
            parseCommand(arrayOf("--settings", "set", "auto-login", "on")),
        )
        assertEquals(
            ParseResult.Success(CliCommand.SetAutoLogin(enabled = false)),
            parseCommand(arrayOf("--settings", "set", "auto-login", "off")),
        )
    }

    @Test
    fun `settings parses and trims allowed ssids`() {
        assertEquals(
            ParseResult.Success(CliCommand.SetAllowedSsids(linkedSetOf("VIT", "G-VIT"))),
            parseCommand(arrayOf("--settings", "set", "allowed-ssids", " VIT, G-VIT ")),
        )
    }

    @Test
    fun `duplicate allowed ssids are collapsed`() {
        assertEquals(
            ParseResult.Success(CliCommand.SetAllowedSsids(linkedSetOf("VIT", "G-VIT"))),
            parseCommand(arrayOf("--settings", "set", "allowed-ssids", "VIT,G-VIT,VIT")),
        )
    }

    @Test
    fun `empty allowed ssid list is rejected`() {
        assertFailure(arrayOf("--settings", "set", "allowed-ssids", ""), "must not be empty")
        assertFailure(arrayOf("--settings", "set", "allowed-ssids", "   "), "must not be empty")
    }

    @Test
    fun `empty allowed ssid entry is rejected`() {
        assertFailure(arrayOf("--settings", "set", "allowed-ssids", "VIT,,G-VIT"), "must not contain empty")
        assertFailure(arrayOf("--settings", "set", "allowed-ssids", "VIT,"), "must not contain empty")
    }

    @Test
    fun `invalid auto-login value is rejected`() {
        assertFailure(arrayOf("--settings", "set", "auto-login", "yes"), "on or off")
    }

    @Test
    fun `unknown command is rejected`() {
        assertFailure(arrayOf("--connect"), "Unknown command")
    }

    @Test
    fun `unknown settings key is rejected`() {
        assertFailure(arrayOf("--settings", "set", "theme", "dark"), "Unknown settings key")
    }

    @Test
    fun `incomplete settings command is rejected`() {
        assertFailure(arrayOf("--settings", "set"), "Usage")
        assertFailure(arrayOf("--settings", "set", "auto-login"), "Usage")
    }

    @Test
    fun `extra arguments are rejected`() {
        assertFailure(arrayOf("--status", "extra"), "does not accept arguments")
        assertFailure(arrayOf("--settings", "extra"), "Usage")
        assertFailure(arrayOf("--settings", "set", "auto-login", "on", "extra"), "Usage")
    }

    private fun assertFailure(args: Array<String>, expectedMessagePart: String) {
        val result = assertIs<ParseResult.Failure>(parseCommand(args))
        assertTrue(
            result.message.contains(expectedMessagePart, ignoreCase = true),
            "Expected '${result.message}' to contain '$expectedMessagePart'",
        )
    }
}
