package com.vinnovateit.latch.cli

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Small native smoke suite: covers the ported logic that has no existing JVM test running here. */
class CliSmokeTest {
    @Test
    fun parsesCommands() {
        assertEquals(CliCommand.Bootstrap, (parseCommand(emptyArray()) as ParseResult.Success).command)
        assertEquals(CliCommand.Help, (parseCommand(arrayOf("--help")) as ParseResult.Success).command)
        assertEquals(CliCommand.Version, (parseCommand(arrayOf("--version")) as ParseResult.Success).command)
        assertEquals(CliCommand.GetSettings, (parseCommand(arrayOf("--settings")) as ParseResult.Success).command)
        assertEquals(
            CliCommand.SetAutoLogin(true),
            (parseCommand(arrayOf("--settings", "set", "auto-login", "on")) as ParseResult.Success).command,
        )
        assertTrue(parseCommand(arrayOf("--bogus")) is ParseResult.Failure)
    }

    @Test
    fun historyUsesIsoTimestampsNewestFirst() {
        val output = CliOutput.history(
            listOf(
                CliSession(1_000L, 2_000L, 10L, 20L),
                CliSession(3_000L, 4_000L, 30L, 40L),
            ),
        )
        assertEquals(
            "start\tend\trx-bytes\ttx-bytes\n" +
                "${Instant.fromEpochMilliseconds(3_000L)}\t${Instant.fromEpochMilliseconds(4_000L)}\t30\t40\n" +
                "${Instant.fromEpochMilliseconds(1_000L)}\t${Instant.fromEpochMilliseconds(2_000L)}\t10\t20\n",
            output,
        )
    }

    @Test
    fun settledLogoMatchesBrandMark() {
        // Captured from the original AWT Area/Path2D rasterizer before the port.
        val expected = listOf(
            "      ▄███▄      ▄▄▄▄▄",
            "    ▄███████▄▄ ▄███████▄",
            "  ▄█████▀▀▀████▄▀████████",
            "  █████     ▀███▄   ▀█████",
            "  █████    ██▄▀██    █████",
            "  █████▄   ████▄     █████",
            "   ████████▄▀████▄▄▄█████▀",
            "    ▀███████▀ ▀▀███████▀",
            "      ▀▀▀▀▀      ▀███▀",
        )
        val actual = IntroRenderer().mark().map(String::trimEnd).filter(String::isNotBlank)
        assertEquals(expected, actual)
    }

    @Test
    fun requestIdHasUuidShape() {
        val id = randomRequestId()
        assertEquals(36, id.length)
        assertTrue(id[14] == '4')
        assertTrue(id[19] in listOf('8', '9', 'a', 'b'))
    }
}
