package com.vinnovateit.latch.core.mp

import kotlinx.coroutines.CoroutineDispatcher

/** Wall clock in epoch milliseconds (JVM System.currentTimeMillis equivalent). */
expect fun nowMillis(): Long

/** Monotonic clock in nanoseconds (JVM System.nanoTime equivalent). */
expect fun nanoTime(): Long

/** I/O dispatcher: JVM IO dispatcher, Default on Kotlin/Native. */
expect val ioDispatcher: CoroutineDispatcher

/** US-locale string formatting. */
expect fun formatUs(format: String, vararg args: Any?): String

/** User home directory (JVM `user.home`). */
expect fun userHome(): String
