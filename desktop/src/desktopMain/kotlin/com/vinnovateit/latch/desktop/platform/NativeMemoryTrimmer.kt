package com.vinnovateit.latch.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * Manages native memory tuning and reclamation for the desktop JVM process.
 *
 * On Linux, glibc's ptmalloc defaults to creating up to 8 * CPU cores memory
 * arenas (64+ arenas on multi-core machines). When threads allocate native memory
 * (via JNI, Skia, GTK, SQLite, OSHI), glibc distributes them across these arenas in
 * 64 MB virtual chunks, causing severe memory fragmentation and preventing freed
 * memory from being returned to the kernel.
 *
 * [init] restricts glibc to 2 arenas via `mallopt(M_ARENA_MAX, 2)`.
 * [trim] requests an immediate release of unmapped native pages via `malloc_trim(0)`.
 */
object NativeMemoryTrimmer {
    private interface LibC : Library {
        fun mallopt(param: Int, value: Int): Int
        fun malloc_trim(pad: Long): Int
    }

    /** glibc M_ARENA_MAX option constant (-8 on Linux). */
    private const val M_ARENA_MAX = -8

    private val libc: LibC? by lazy {
        if (System.getProperty("os.name").contains("Linux", ignoreCase = true)) {
            runCatching { Native.load("c", LibC::class.java) }.getOrNull()
        } else {
            null
        }
    }

    fun init() {
        libc?.runCatching {
            mallopt(M_ARENA_MAX, 2)
        }
    }

    fun trim() {
        System.gc()
        libc?.runCatching {
            malloc_trim(0L)
        }
    }
}
