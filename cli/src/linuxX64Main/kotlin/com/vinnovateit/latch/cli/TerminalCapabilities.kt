package com.vinnovateit.latch.cli

/**
 * What the attached terminal can be asked to do. Everything that draws to the
 * terminal degrades against this rather than assuming a capable emulator.
 */
data class TerminalCapabilities(
    val interactive: Boolean,
    val ansi: Boolean,
    val trueColor: Boolean,
    val noColor: Boolean,
    val unicode: Boolean,
)

/**
 * What the terminal is assumed to decode. Kotlin/Native has no java.nio.charset
 * or console charset, so UTF-8 is inferred from the locale the process was
 * started with; a non-UTF-8 locale falls back to the one-line banner.
 */
private fun localeIsUtf8(environment: Map<String, String>): Boolean {
    val locale = listOf("LC_ALL", "LC_CTYPE", "LANG")
        .firstNotNullOfOrNull(environment::get)
        .orEmpty()
    return locale.contains("UTF-8", ignoreCase = true) || locale.contains("UTF8", ignoreCase = true)
}

fun detectTerminalCapabilities(
    terminal: TerminalIO,
    environment: Map<String, String> = CliEnv.all(),
    osName: String = CliEnv.osName,
    unicode: Boolean = CliConsole.stdoutIsTty && localeIsUtf8(environment),
): TerminalCapabilities {
    val interactive = terminal.interactive
    val noColor = environment.containsKey("NO_COLOR")
    val term = environment["TERM"].orEmpty()
    val windows = osName.startsWith("Windows", ignoreCase = true)
    // Windows consoles only handle escapes in terminals that advertise it;
    // cmd.exe without one of these renders them as literal garbage.
    val windowsAnsi = environment.containsKey("WT_SESSION") ||
        environment.containsKey("ANSICON") ||
        environment["ConEmuANSI"].equals("ON", ignoreCase = true) ||
        term.contains("xterm", ignoreCase = true)
    val ansi = interactive && !noColor && !term.equals("dumb", ignoreCase = true) && (!windows || windowsAnsi)
    val colorTerm = environment["COLORTERM"].orEmpty()
    val trueColor = ansi && (
        colorTerm.contains("truecolor", ignoreCase = true) ||
            colorTerm.contains("24bit", ignoreCase = true) ||
            environment.containsKey("WT_SESSION")
        )
    return TerminalCapabilities(interactive, ansi, trueColor, noColor, unicode)
}
