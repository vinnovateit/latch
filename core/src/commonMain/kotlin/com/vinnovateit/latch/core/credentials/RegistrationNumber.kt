package com.vinnovateit.latch.core.credentials

private val PATTERN = Regex("^[0-9]{2}[A-Z]{3}[0-9]{4}$")

/**
 * The single source of truth for the VIT registration-number shape
 * (`YYAAAXXXX`), shared by Android, Desktop and the CLI so the three surfaces
 * cannot drift into accepting different inputs.
 */
object RegistrationNumber {
    /** Trims and uppercases; does not strip punctuation. */
    fun normalize(raw: String): String = raw.trim().uppercase()

    /** [value] must already be normalized -- this does not normalize for you. */
    fun isValid(value: String): Boolean = PATTERN.matches(value)
}
