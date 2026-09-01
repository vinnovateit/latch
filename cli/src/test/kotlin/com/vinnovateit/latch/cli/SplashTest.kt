package com.vinnovateit.latch.cli

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplashTest {
    @Test
    fun `same seed and progress produce the same frame`() {
        val capabilities = SplashCapabilities(interactive = true, ansi = false, trueColor = false, noColor = true)

        val first = SplashRenderer(seed = 42).frame(progress = 0.65, capabilities)
        val second = SplashRenderer(seed = 42).frame(progress = 0.65, capabilities)

        assertEquals(first, second)
    }

    @Test
    fun `frame is a 56 by 20 braille composition`() {
        val capabilities = SplashCapabilities(interactive = true, ansi = false, trueColor = false, noColor = true)

        val frame = SplashRenderer().frame(progress = 1.0, capabilities)

        assertEquals(20, frame.size)
        assertTrue(frame.all { it.length == 56 })
        assertTrue(frame.joinToString("").any { it.code in 0x2801..0x28ff })
    }

    @Test
    fun `no color frame contains no terminal escapes`() {
        val capabilities = SplashCapabilities(interactive = true, ansi = true, trueColor = true, noColor = true)

        val frame = SplashRenderer().frame(progress = 1.0, capabilities)

        assertFalse(frame.joinToString("").contains('\u001b'))
    }

    @Test
    fun `true color uses the Latch brand red`() {
        val capabilities = SplashCapabilities(interactive = true, ansi = true, trueColor = true, noColor = false)

        val frame = SplashRenderer().frame(progress = 1.0, capabilities)

        assertTrue(frame.joinToString("").contains("\u001b[38;2;192;18;33m"))
    }

    @Test
    fun `basic ansi falls back to standard red`() {
        val capabilities = SplashCapabilities(interactive = true, ansi = true, trueColor = false, noColor = false)

        val frame = SplashRenderer().frame(progress = 1.0, capabilities)

        assertTrue(frame.joinToString("").contains("\u001b[31m"))
        assertFalse(frame.joinToString("").contains("38;2"))
    }

    @Test
    fun `noninteractive output skips splash`() = runBlocking {
        val terminal = SplashTerminal(interactive = false)
        val capabilities = SplashCapabilities(interactive = false, ansi = false, trueColor = false, noColor = true)

        showSplash(terminal, capabilities, frameDelayMillis = 0)

        assertEquals("", terminal.output)
    }

    @Test
    fun `plain terminal receives one static frame without cursor controls`() = runBlocking {
        val terminal = SplashTerminal(interactive = true)
        val capabilities = SplashCapabilities(interactive = true, ansi = false, trueColor = false, noColor = true)

        showSplash(terminal, capabilities, frameDelayMillis = 0)

        assertFalse(terminal.output.contains('\u001b'))
        assertEquals(20, terminal.output.trimEnd().lines().size)
    }

    @Test
    fun `animated terminal hides and restores the cursor`() = runBlocking {
        val terminal = SplashTerminal(interactive = true)
        val capabilities = SplashCapabilities(interactive = true, ansi = true, trueColor = false, noColor = false)

        showSplash(terminal, capabilities, frameDelayMillis = 0)

        assertTrue(terminal.output.startsWith("\u001b[?25l"))
        assertTrue(terminal.output.endsWith("\u001b[0m\u001b[?25h\n"))
        assertEquals(9, "\u001b[20A".toRegex(RegexOption.LITERAL).findAll(terminal.output).count())
    }
}

private class SplashTerminal(override val interactive: Boolean) : TerminalIO {
    private val buffer = StringBuilder()
    val output: String get() = buffer.toString()

    override fun print(text: String) {
        buffer.append(text)
    }

    override fun println(text: String) {
        buffer.append(text).append('\n')
    }

    override fun readLine(prompt: String): String? = null
    override fun readSecret(prompt: String): CharArray? = null
}
