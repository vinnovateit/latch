package com.vinnovateit.latch.desktop

import com.vinnovateit.latch.core.engine.LatchCommand
import com.vinnovateit.latch.core.runtime.DesktopEngineRuntime
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.formatBitsPerSecond
import com.vinnovateit.latch.core.stats.formatClockTime
import com.vinnovateit.latch.desktop.platform.TrayNotifier
import com.vinnovateit.latch.desktop.platform.windows.WindowsBalloonNotifier
import com.vinnovateit.latch.desktop.updater.GithubUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val APP_DISPLAY_NAME = "LATCH by VinnovateIT"

/** How long the startup update check waits to get past the captive portal. */
private const val LATCH_WAIT_BEFORE_UPDATE_CHECK_MS = 60_000L

/**
 * Composition root. Everything is constructed once here, in dependency order,
 * and handed to whoever needs it -- replacing the Android app's hand-rolled
 * `object` singletons that were initialized opportunistically from whichever
 * component happened to run first.
 */
class LatchApp private constructor(
    internal val runtime: DesktopEngineRuntime,
    val notifier: TrayNotifier,
    val updater: GithubUpdater,
) {
    val platform get() = runtime.platform
    val sessions get() = runtime.sessions
    val engine get() = runtime.engine

    companion object {
        fun create(echoLogsToStdout: Boolean): LatchApp {
            val notifier = TrayNotifier()
            val runtime = runBlocking {
                DesktopEngineRuntime.create(notifier, echoLogsToStdout)
            }

            val updater = GithubUpdater(
                buildInfo = runtime.platform.buildInfo,
                logger = runtime.platform.logger,
            )

            return LatchApp(runtime, notifier, updater)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        if (AppPaths.isWindows) WindowsBalloonNotifier.start(platform.logger)
        applyAutostartDefault()
        runtime.start()

        // Drive the tray tooltip from live session data. This is the 2-second
        // update path, so it must stay on showOngoing (tooltip) and never become
        // a balloon.
        scope.launch {
            sessions.liveStatus.collect { status ->
                if (status == null || status.liveData.isEmpty()) {
                    notifier.showOngoing(APP_DISPLAY_NAME, "Not latched")
                    return@collect
                }
                val latest = status.liveData.last().usage
                val downloadDominant = latest.rxBps >= latest.txBps
                val dominant = if (downloadDominant) latest.rxBps else latest.txBps
                val arrow = if (downloadDominant) "↓" else "↑"
                val (value, unit) = formatBitsPerSecond(dominant, SettingsManager.speedUnits.value)
                notifier.showOngoing(
                    APP_DISPLAY_NAME,
                    "$arrow $value $unit • since ${formatClockTime(status.startTimeMillis)}",
                )
            }
        }

        // Announce real state changes only.
        scope.launch {
            var wasLatched = false
            engine.isLatched.collect { latched ->
                if (latched && !wasLatched) {
                    notifier.notifyTransient("Connected", "Latched onto Wi-Fi.")
                } else if (!latched && wasLatched) {
                    notifier.notifyTransient("Disconnected", "No longer latched.")
                }
                wasLatched = latched
            }
        }

        // Switch the radio back on if the user left Wi-Fi off, *then* probe.
        // enableWifi() shells out to PowerShell and waits for the adapter to
        // associate, so it must stay off the main thread; probing first would
        // just report "no Wi-Fi" and give up before the radio came up.
        scope.launch(Dispatchers.IO) {
            if (!platform.wifi.isWifiEnabled()) {
                val enabled = platform.wifi.enableWifi()
                platform.logger.d(
                    "LatchApp",
                    if (enabled) "Wi-Fi was off at launch; turned it back on."
                    else "Wi-Fi was off at launch and could not be enabled.",
                )
            }

            // Mirror the Android launch behaviour: probe once at startup so an
            // already-authenticated network is detected without user action.
            engine.submit(
                if (SettingsManager.autoLogin.value) LatchCommand.CheckAndLogin
                else LatchCommand.SilentCheck
            )
        }

        // Background update check on startup, installed builds only. A dev
        // (`gradle run`) build would otherwise nag a developer and burn the
        // unauthenticated API budget; the manual button still works.
        if (platform.buildInfo.isInstalled) {
            updater.cleanStaleDownloads()
            scope.launch {
                // Wait for the portal before asking GitHub anything. Un-latched,
                // the request is answered by VIT's captive portal with HTML, the
                // JSON parse throws, and Settings is left reading "Update check
                // failed" -- with no retry, since this check only fires once per
                // launch. If we never latch (already on a normal network, Wi-Fi
                // down) the check still runs; the wait is a delay, not a gate.
                withTimeoutOrNull(LATCH_WAIT_BEFORE_UPDATE_CHECK_MS) {
                    engine.isLatched.first { it }
                }
                updater.check()
            }
        }
    }

    /**
     * Turns on start-at-login the first time an *installed* build runs.
     *
     * Only applied once, and only recorded as applied if it actually took effect,
     * so:
     *  - a user who switches it off is never silently re-enabled;
     *  - running from `gradle run` does not mark it done (appExePath() rejects a
     *    path that is not Latch.exe), so the real install still gets its chance.
     */
    private fun applyAutostartDefault() {
        if (!platform.capabilities.supportsAutostart) return
        if (SettingsManager.autostartDefaultApplied) return

        platform.systemActions.setAutostart(true)
        if (platform.systemActions.isAutostartEnabled()) {
            SettingsManager.autostartDefaultApplied = true
            platform.logger.d("LatchApp", "Enabled start-at-login by default.")
        } else {
            platform.logger.d(
                "LatchApp",
                "Not an installed build; leaving start-at-login for the installed app.",
            )
        }
    }

    fun shutdown() {
        runCatching { runBlocking { runtime.close() } }
    }

    /**
     * Fetches the update and leaves it at "ready to install". Installing is the
     * user's next, separate decision -- see the "Install and restart" button.
     */
    fun downloadUpdate() {
        scope.launch { updater.download() }
    }

    fun cancelUpdateDownload() = updater.cancelDownload()
}
