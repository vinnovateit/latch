package com.vinnovateit.latch.core.mp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.Locale

actual fun nowMillis(): Long = System.currentTimeMillis()
actual fun nanoTime(): Long = System.nanoTime()
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
actual val httpDispatcher: CoroutineDispatcher = Dispatchers.IO
actual fun formatUs(format: String, vararg args: Any?): String = String.format(Locale.US, format, *args)

actual fun userHome(): String = System.getProperty("user.home") ?: "."
