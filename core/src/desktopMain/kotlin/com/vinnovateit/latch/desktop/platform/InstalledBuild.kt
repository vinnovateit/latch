package com.vinnovateit.latch.desktop.platform

/**
 * Resolves whether the running process is an installed (jpackage MSI) build
 * rather than a Gradle-run or build-output app image.
 *
 * jpackage sets `jpackage.app-path` for a build-output app image just as much
 * as for a real install, so the filename alone is not enough: build/output
 * locations are rejected explicitly. Being conservative is the right trade
 * here -- a false "installed" would auto-update a dev build, whereas a false
 * "not installed" only skips an update check a developer can trigger by hand.
 */
object InstalledBuild {

    private val validExeNames = listOf("latch.exe", "latch")

    private val devMarkers = listOf(
        "/build/compose/", "/build/", "/out/", "/.gradle/", "/target/",
    )

    /** The resolved executable path, or null if this is not an installed build. */
    val path: String? by lazy {
        val candidate = System.getProperty("jpackage.app-path")
            ?: ProcessHandle.current().info().command().orElse(null)
            ?: return@lazy null

        val name = candidate.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (name !in validExeNames) return@lazy null

        val normalized = candidate.replace('\\', '/').lowercase()
        if (devMarkers.any { normalized.contains(it) }) null else candidate
    }

    val isInstalled: Boolean get() = path != null
}
