package com.vinnovateit.latch.cli

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val terminal = SystemTerminal
    val lifecycle = createSystemCliLifecycle()
    val exitCode = runBlocking {
        runCli(args, terminal, lifecycle = lifecycle) { command ->
            createCoordinatedCliBackend(command, terminal)
        }
    }
    if (exitCode != EXIT_SUCCESS) exitProcess(exitCode)
}

internal object SystemTerminal : TerminalIO {
    override val interactive: Boolean
        get() = CliConsole.isAvailable

    override fun print(text: String) = kotlin.io.print(text)

    override fun println(text: String) = kotlin.io.println(text)

    override fun readLine(prompt: String): String? = CliConsole.readLine(prompt)

    override fun readSecret(prompt: String): CharArray? = CliConsole.readSecret(prompt)
}
