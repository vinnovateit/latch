package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.credentials.RegistrationNumber

internal data class PromptedCredentials(
    val userId: String,
    val password: CharArray,
)

internal fun promptForCredentials(terminal: TerminalIO): Result<PromptedCredentials> {
    val userId = RegistrationNumber.normalize(terminal.readLine("User ID: ").orEmpty())
    if (userId.isEmpty()) return Result.failure(IllegalArgumentException("A user ID is required."))
    if (!RegistrationNumber.isValid(userId)) {
        return Result.failure(IllegalArgumentException("Invalid registration number."))
    }

    val password = terminal.readSecret("Password: ")
    if (password == null || password.isEmpty()) {
        password?.fill('\u0000')
        return Result.failure(IllegalArgumentException("A password is required."))
    }

    return Result.success(PromptedCredentials(userId, password))
}
