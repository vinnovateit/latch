package com.vinnovateit.latch.desktop.platform.mac

import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.SystemActions
import com.vinnovateit.latch.desktop.platform.BoundedCommandRunner
import com.vinnovateit.latch.desktop.platform.CommandOutcome
import com.vinnovateit.latch.desktop.platform.InstalledBuild
import com.vinnovateit.latch.desktop.platform.SecureFileWriter
import java.io.File
import java.nio.file.Files

private const val OPEN = "/usr/bin/open"

/**
 * macOS OS actions. Start at login is a per-user LaunchAgent: launchd reads
 * ~/Library/LaunchAgents at login and starts Latch with `--hidden`. It needs
 * no entitlement or signature, so it works for every build Latch ships, and
 * macOS 13+ lists it under Login Items, where the user can switch it off.
 */
class MacSystemActions internal constructor(
    private val logger: Logger,
    private val launchAgentsDir: File,
    private val installedExecutable: () -> String?,
    /** Runs a command to completion or its deadline; a seam so tests can see what is run. */
    private val command: (List<String>) -> CommandOutcome = BoundedCommandRunner(timeoutMillis = 5_000)::run,
) : SystemActions {

    constructor(logger: Logger) : this(
        logger = logger,
        launchAgentsDir = File(System.getProperty("user.home"), "Library/LaunchAgents"),
        installedExecutable = { InstalledBuild.path },
    )

    internal companion object {
        const val TAG = "MacSystemActions"
        const val LAUNCH_AGENT_LABEL = "com.vinnovateit.latch.login"
        const val BUNDLE_ID = "com.vinnovateit.latch"

        // System Settings' Wi-Fi pane (macOS 13+), then the Network pane of
        // System Preferences (macOS 12), which is where Wi-Fi lived.
        val WIFI_SETTINGS = listOf(
            "x-apple.systempreferences:com.apple.wifi-settings-extension",
            "x-apple.systempreferences:com.apple.preference.network",
        )

        // Location Services under Privacy & Security (macOS 13+), then under
        // Security & Privacy in System Preferences (macOS 12).
        val LOCATION_SETTINGS = listOf(
            "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy_LocationServices",
            "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices",
        )

        /** The launchd job that starts [executable] at login. Paths are XML-escaped. */
        fun launchAgentPlist(executable: String): String = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key>
                <string>$LAUNCH_AGENT_LABEL</string>
                <key>ProgramArguments</key>
                <array>
                    <string>${xmlEscape(executable)}</string>
                    <string>--hidden</string>
                </array>
                <key>RunAtLoad</key>
                <true/>
                <key>ProcessType</key>
                <string>Interactive</string>
                <key>LimitLoadToSessionType</key>
                <string>Aqua</string>
                <key>AssociatedBundleIdentifiers</key>
                <string>$BUNDLE_ID</string>
            </dict>
            </plist>
        """.trimIndent() + "\n"

        private fun xmlEscape(value: String): String = buildString {
            value.forEach { c ->
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(c)
                }
            }
        }
    }

    private val launchAgent: File get() = File(launchAgentsDir, "$LAUNCH_AGENT_LABEL.plist")

    override fun openWifiSettings() {
        if (WIFI_SETTINGS.any { open(it) }) return
        logger.w(TAG, "Could not open Wi-Fi settings.")
    }

    override fun openLocationSettings() {
        if (LOCATION_SETTINGS.any { open(it) }) return
        logger.w(TAG, "Could not open Location Services settings.")
    }

    override fun openUrl(url: String) {
        // Only web links: anything else handed to `open` could name a local app or file.
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            logger.w(TAG, "Refusing to open a non-web URL")
            return
        }
        if (!open(url)) logger.w(TAG, "Could not open URL: $url")
    }

    /** `open` returns as soon as Launch Services has the request, so the bound is generous. */
    private fun open(target: String): Boolean =
        command(listOf(OPEN, target)) is CommandOutcome.Succeeded

    /**
     * Only an installed Latch.app is registered: a Gradle run is a bare JVM,
     * and an app Gatekeeper launched from a translocated read-only copy has a
     * path that is gone by the next login.
     */
    override fun setAutostart(enabled: Boolean) {
        val executable = installedExecutable()
        if (executable == null || !executable.contains(".app/Contents/MacOS/")) {
            logger.w(TAG, "Not running from an installed Latch.app; refusing to set start at login.")
            return
        }
        if (executable.contains("/AppTranslocation/")) {
            logger.w(TAG, "Latch is running from a translocated copy; move Latch.app to Applications to start it at login.")
            return
        }
        try {
            if (enabled) {
                launchAgentsDir.mkdirs()
                SecureFileWriter(ownerOnly = true).replace(launchAgent, launchAgentPlist(executable).toByteArray(Charsets.UTF_8))
                logger.d(TAG, "Start at login enabled -> ${launchAgent.absolutePath}")
            } else {
                Files.deleteIfExists(launchAgent.toPath())
                logger.d(TAG, "Start at login disabled")
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to update start at login", e)
        }
    }

    override fun isAutostartEnabled(): Boolean = launchAgent.isFile
}
