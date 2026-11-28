@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.core.mp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlin.time.TimeSource
import kotlin.math.pow
import platform.posix.gettimeofday
import platform.posix.timeval

actual fun nowMillis(): Long = memScoped {
    val tv = alloc<timeval>()
    gettimeofday(tv.ptr, null)
    tv.tv_sec.toLong() * 1_000L + tv.tv_usec.toLong() / 1_000L
}
private val monotonic = TimeSource.Monotonic
actual fun nanoTime(): Long = monotonic.markNow().elapsedNow().inWholeNanoseconds
// Dispatchers.IO is internal on Kotlin/Native. Default is the small shared CPU
// pool that also runs the stats recompute and UI state collection, so give
// blocking curl/DB work its own fixed Worker pool instead of starving it.
@OptIn(DelicateCoroutinesApi::class)
actual val ioDispatcher: CoroutineDispatcher = newFixedThreadPoolContext(8, "latch-io")

@OptIn(DelicateCoroutinesApi::class)
actual val httpDispatcher: CoroutineDispatcher = newFixedThreadPoolContext(12, "latch-http")

/** Minimal `String.format(Locale.US, …)` covering the patterns used by Latch. */
actual fun formatUs(format: String, vararg args: Any?): String {
    val out = StringBuilder()
    var i = 0
    var arg = 0
    while (i < format.length) {
        if (format[i] != '%') {
            out.append(format[i++])
            continue
        }
        if (i + 1 < format.length && format[i + 1] == '%') {
            out.append('%'); i += 2; continue
        }
        var j = i + 1
        var pad = ' '
        if (j < format.length && format[j] == '0') { pad = '0'; j++ }
        var width = 0
        while (j < format.length && format[j].isDigit()) {
            width = width * 10 + (format[j] - '0'); j++
        }
        var precision = -1
        if (j < format.length && format[j] == '.') {
            j++; precision = 0
            while (j < format.length && format[j].isDigit()) {
                precision = precision * 10 + (format[j] - '0'); j++
            }
        }
        val spec = format[j]
        val value = args.getOrNull(arg++)
        val text = when (spec) {
            'd' -> (value as? Number)?.toLong()?.toString().orEmpty()
            'X' -> (value as? Number)?.toLong()?.let {
                val hex = it.toString(16).uppercase()
                if (width > hex.length) pad.toString().repeat(width - hex.length) + hex else hex
            }.orEmpty()
            'f' -> (value as? Number)?.toDouble()?.let { v ->
                buildString {
                    append(v.toLong().toString())
                    val p = if (precision >= 0) precision else 6
                    if (p > 0) {
                        val frac = kotlin.math.abs((v - v.toLong()) * 10.0.pow(p)).toLong()
                        append('.').append(frac.toString().padStart(p, '0'))
                    }
                }
            }.orEmpty()
            's' -> value?.toString().orEmpty()
            else -> value?.toString().orEmpty()
        }
        val rendered = if (spec == 'X') text else if (width > text.length) pad.toString().repeat(width - text.length) + text else text
        out.append(rendered)
        i = j + 1
    }
    return out.toString()
}

actual fun userHome(): String = platform.posix.getenv("HOME")?.toKString() ?: "."
