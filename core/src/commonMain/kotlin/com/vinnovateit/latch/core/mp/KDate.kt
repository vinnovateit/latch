package com.vinnovateit.latch.core.mp

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Multiplatform stand-ins for the `java.util` / `java.text` types the shared code uses. */

class KDate(val time: Long) {
    constructor() : this(nowMillis())
}

object KLocale {
    val US = KLocale
    fun getDefault(): KLocale = US
}

class KTimeZone(val id: String) {
    companion object {
        fun getDefault(): KTimeZone = KTimeZone("default")
        fun getTimeZone(id: String): KTimeZone = KTimeZone(id)
    }
    internal val kotlinZone: TimeZone
        get() = if (id == "UTC" || id == "GMT") TimeZone.UTC else TimeZone.currentSystemDefault()
}

private fun local(ms: Long, zone: TimeZone): LocalDateTime = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
private fun epoch(value: LocalDateTime, zone: TimeZone): Long = value.toInstant(zone).toEpochMilliseconds()

private val MONTHS_SHORT = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val MONTHS_LONG = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val DAYS_SHORT = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

class KCalendar private constructor(private val timeZone: KTimeZone) {
    companion object {
        const val YEAR = 1
        const val MONTH = 2
        const val DAY_OF_MONTH = 5
        const val DAY_OF_YEAR = 6
        const val HOUR_OF_DAY = 11
        const val MINUTE = 12
        const val SECOND = 13
        const val MILLISECOND = 14
        const val JANUARY = 0

        fun getInstance(): KCalendar = KCalendar(nowMillis(), KTimeZone.getDefault())
        fun getInstance(zone: KTimeZone): KCalendar = KCalendar(nowMillis(), zone)
    }

    var timeInMillis: Long = 0L

    constructor(millis: Long = nowMillis(), zone: KTimeZone = KTimeZone.getDefault()) : this(zone) { timeInMillis = millis }

    private val zone: TimeZone get() = timeZone.kotlinZone

    fun clear() { timeInMillis = 0L }

    val time: KDate get() = KDate(timeInMillis)

    fun get(field: Int): Int {
        val value = local(timeInMillis, zone)
        return when (field) {
            YEAR -> value.year
            MONTH -> value.monthNumber - 1
            DAY_OF_MONTH -> value.dayOfMonth
            DAY_OF_YEAR -> value.dayOfYear
            HOUR_OF_DAY -> value.hour
            MINUTE -> value.minute
            SECOND -> value.second
            MILLISECOND -> value.nanosecond / 1_000_000
            else -> 0
        }
    }

    fun set(field: Int, value: Int) {
        val current = local(timeInMillis, zone)
        timeInMillis = epoch(when (field) {
            YEAR -> LocalDateTime(value, current.monthNumber, current.dayOfMonth, current.hour, current.minute, current.second, current.nanosecond)
            MONTH -> LocalDateTime(current.year, value + 1, current.dayOfMonth, current.hour, current.minute, current.second, current.nanosecond)
            DAY_OF_MONTH -> LocalDateTime(current.year, current.monthNumber, value, current.hour, current.minute, current.second, current.nanosecond)
            DAY_OF_YEAR -> Instant.fromEpochMilliseconds(timeInMillis + (value - current.dayOfYear) * 86_400_000L).toLocalDateTime(zone)
            HOUR_OF_DAY -> LocalDateTime(current.year, current.monthNumber, current.dayOfMonth, value, current.minute, current.second, current.nanosecond)
            MINUTE -> LocalDateTime(current.year, current.monthNumber, current.dayOfMonth, current.hour, value, current.second, current.nanosecond)
            SECOND -> LocalDateTime(current.year, current.monthNumber, current.dayOfMonth, current.hour, current.minute, value, current.nanosecond)
            MILLISECOND -> LocalDateTime(current.year, current.monthNumber, current.dayOfMonth, current.hour, current.minute, current.second, value * 1_000_000)
            else -> current
        }, zone)
    }

    fun set(year: Int, month: Int, day: Int) {
        val current = local(timeInMillis, zone)
        timeInMillis = epoch(LocalDateTime(year, month + 1, day, current.hour, current.minute, current.second, current.nanosecond), zone)
    }

    fun set(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int) {
        val current = local(timeInMillis, zone)
        timeInMillis = epoch(LocalDateTime(year, month + 1, day, hour, minute, second, current.nanosecond), zone)
    }

    fun add(field: Int, amount: Int) {
        val current = local(timeInMillis, zone)
        timeInMillis = when (field) {
            YEAR -> epoch(LocalDateTime(current.year + amount, current.monthNumber, current.dayOfMonth, current.hour, current.minute, current.second, current.nanosecond), zone)
            MONTH -> {
                val total = current.year * 12 + current.monthNumber - 1 + amount
                epoch(LocalDateTime(total / 12, total % 12 + 1, current.dayOfMonth.coerceAtMost(28), current.hour, current.minute, current.second, current.nanosecond), zone)
            }
            DAY_OF_YEAR, DAY_OF_MONTH -> timeInMillis + amount.toLong() * 86_400_000L
            HOUR_OF_DAY -> timeInMillis + amount * 3_600_000L
            MINUTE -> timeInMillis + amount * 60_000L
            SECOND -> timeInMillis + amount * 1_000L
            else -> timeInMillis + amount
        }
    }

    fun clone(): KCalendar = KCalendar(timeZone).also { it.timeInMillis = timeInMillis }
    fun after(other: KCalendar): Boolean = timeInMillis > other.timeInMillis
    fun before(other: KCalendar): Boolean = timeInMillis < other.timeInMillis
}

private data class Token(val literal: String? = null, val pattern: String? = null)

private fun tokenize(pattern: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var i = 0
    while (i < pattern.length) {
        if (pattern[i] == '\'') {
            val end = pattern.indexOf('\'', i + 1)
            if (end < 0) { tokens += Token(literal = pattern.substring(i + 1)); break }
            tokens += Token(literal = pattern.substring(i + 1, end)); i = end + 1
        } else if (pattern[i].isLetter()) {
            val start = i
            while (i < pattern.length && pattern[i] == pattern[start]) i++
            tokens += Token(pattern = pattern.substring(start, i))
        } else {
            val start = i
            while (i < pattern.length && !pattern[i].isLetter() && pattern[i] != '\'') i++
            tokens += Token(literal = pattern.substring(start, i))
        }
    }
    return tokens
}

class KSimpleDateFormat(private val pattern: String, private val locale: Any? = null) {
    fun format(date: KDate): String {
        val zone = TimeZone.currentSystemDefault()
        val value = local(date.time, zone)
        return tokenize(pattern).joinToString("") { token ->
            token.literal?.let { return@joinToString it }
            when (token.pattern) {
                "yyyy" -> value.year.toString().padStart(4, '0')
                "yy" -> (value.year % 100).toString().padStart(2, '0')
                "MMMM" -> MONTHS_LONG[value.monthNumber - 1]
                "MMM" -> MONTHS_SHORT[value.monthNumber - 1]
                "MM" -> value.monthNumber.toString().padStart(2, '0')
                "M" -> value.monthNumber.toString()
                "dd" -> value.dayOfMonth.toString().padStart(2, '0')
                "d" -> value.dayOfMonth.toString()
                "EEE" -> DAYS_SHORT[value.dayOfWeek.ordinal]
                "HH" -> value.hour.toString().padStart(2, '0')
                "hh" -> ((value.hour + 11) % 12 + 1).toString().padStart(2, '0')
                "mm" -> value.minute.toString().padStart(2, '0')
                "ss" -> value.second.toString().padStart(2, '0')
                "SSS" -> (value.nanosecond / 1_000_000).toString().padStart(3, '0')
                "a" -> if (value.hour < 12) "AM" else "PM"
                else -> token.pattern.orEmpty()
            }
        }
    }

    fun parse(text: String): KDate? {
        var regex = ""
        val fields = mutableListOf<String>()
        for (token in tokenize(pattern)) {
            if (token.literal != null) {
                regex += Regex.escape(token.literal)
            } else {
                val field = token.pattern!!
                fields += field
                regex += when (field) {
                    "yyyy" -> "(\\d{4})"
                    "yy" -> "(\\d{2})"
                    "MMMM", "MMM", "EEE", "a" -> "([A-Za-z]+)"
                    else -> "(\\d{1,4})"
                }
            }
        }
        val match = Regex("^$regex$").find(text.trim()) ?: return null
        var year = 1970; var month = 1; var day = 1
        var hour = 0; var minute = 0; var second = 0; var milli = 0; var pm = false
        var hasAmPm = false
        var index = 1
        for (field in fields) {
            val part = match.groupValues.getOrNull(index++) ?: return null
            when (field) {
                "yyyy" -> year = part.toIntOrNull() ?: return null
                "yy" -> { val y = part.toIntOrNull() ?: return null; year = if (y <= 69) 2000 + y else 1900 + y }
                "MMMM" -> month = (MONTHS_LONG.indexOfFirst { it.equals(part, ignoreCase = true) } + 1).also { if (it == 0) return null }
                "MMM" -> month = (MONTHS_SHORT.indexOfFirst { it.equals(part, ignoreCase = true) } + 1).also { if (it == 0) return null }
                "MM", "M" -> month = part.toIntOrNull() ?: return null
                "dd", "d" -> day = part.toIntOrNull() ?: return null
                "HH", "hh" -> hour = part.toIntOrNull() ?: return null
                "mm" -> minute = part.toIntOrNull() ?: return null
                "ss" -> second = part.toIntOrNull() ?: return null
                "SSS" -> milli = part.toIntOrNull() ?: return null
                "a" -> { hasAmPm = true; pm = part.equals("PM", ignoreCase = true) }
            }
        }
        if (hasAmPm) {
            if (hour == 12) hour = 0
            if (pm) hour += 12
        }
        return runCatching {
            KDate(epoch(LocalDateTime(year, month, day, hour, minute, second, milli * 1_000_000), TimeZone.currentSystemDefault()))
        }.getOrNull()
    }
}
