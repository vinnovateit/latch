package com.vinnovateit.latch.cli

internal data class PromptedCredentials(
    val userId: String,
    val password: CharArray,
)

internal fun promptForCredentials(terminal: TerminalIO): Result<PromptedCredentials> {
    val userId = terminal.readLine("User ID: ")?.trim().orEmpty()
    if (userId.isEmpty()) return Result.failure(IllegalArgumentException("A user ID is required."))

    val password = terminal.readSecret("Password: ")
    if (password == null || password.isEmpty()) {
        password?.fill('\u0000')
        return Result.failure(IllegalArgumentException("A password is required."))
    }

    return Result.success(PromptedCredentials(userId, password))
}
