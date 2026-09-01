package com.vinnovateit.latch.cli

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val terminal = SystemTerminal
    val exitCode = runBlocking {
        runCli(args, terminal) { command ->
            createCoordinatedCliBackend(command, terminal)
        }
    }
    if (exitCode != EXIT_SUCCESS) exitProcess(exitCode)
}

internal object SystemTerminal : TerminalIO {
    private val console get() = System.console()

    override val interactive: Boolean
        get() = console != null

    override fun print(text: String) = kotlin.io.print(text)

    override fun println(text: String) = kotlin.io.println(text)

    override fun readLine(prompt: String): String? {
        console?.let { return it.readLine("%s", prompt) }
        print(prompt)
        return readlnOrNull()
    }

    override fun readSecret(prompt: String): CharArray? = console?.readPassword("%s", prompt)
}
