package com.vinnovateit.latch.desktop.updater

import com.vinnovateit.latch.core.platform.BuildInfo
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.desktop.AppPaths
import com.vinnovateit.latch.desktop.platform.InstalledBuild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset

private const val GITHUB_API = "https://api.github.com/repos/vinnovateit/latch/releases/latest"
private const val MSI_PATTERN = "Latch-"
private const val PIPE = 32 * 1024

// HttpURLConnection defaults to *no* timeout. On a captive-portal network a
// half-open socket would otherwise park the UI on "Downloading... 0%" forever,
// with no way out but killing the app.
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000

private const val USER_AGENT = "Latch-Updater"

@Serializable
private data class GithubRelease(
    val tag_name: String,
    val body: String,
    val assets: List<GithubAsset>,
)

@Serializable
private data class GithubAsset(
    val name: String,
    val browser_download_url: String,
    val size: Long,
)

private data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {
    override fun compareTo(other: Semver): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
}

class GithubUpdater(
    private val buildInfo: BuildInfo,
    private val logger: Logger,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile
    private var cancelRequested = false

    /**
     * @param force Bypasses the once-a-day gate. The manual "Check for Updates"
     * button passes true; the silent startup check leaves it false so opening
     * the app several times in a day doesn't re-hit the API on every launch.
     * Persisted (not just in-memory) since [GithubUpdater] is recreated fresh
     * each process start.
     */
    suspend fun check(force: Boolean = false) = withContext(Dispatchers.IO) {
        val today = LocalDate.now(ZoneOffset.UTC).toEpochDay()
        if (!force && SettingsManager.lastUpdateCheckEpochDay == today) {
            logger.d("GithubUpdater", "Skipping check — already checked today")
            return@withContext
        }
        _state.value = UpdateState.Checking
        try {
            val release = fetchLatestRelease()
            val packageAsset = release.assets.find { asset ->
                if (AppPaths.isWindows) {
                    asset.name.startsWith(MSI_PATTERN) && asset.name.endsWith(".msi")
                } else {
                    asset.name.contains("Latch", ignoreCase = true) &&
                        (asset.name.endsWith(".deb") || asset.name.endsWith(".AppImage") || asset.name.endsWith(".tar.gz"))
                }
            }
            if (packageAsset == null) {
                logger.d("GithubUpdater", "No compatible release asset in latest release")
                _state.value = UpdateState.UpToDate
                SettingsManager.lastUpdateCheckEpochDay = today
                return@withContext
            }
            val latestTag = release.tag_name.removePrefix("v")
            val cmp = compareVersions(latestTag, buildInfo.versionName)
            if (cmp == null) {
                logger.e(
                    "GithubUpdater",
                    "Could not compare versions: latest='$latestTag', current='${buildInfo.versionName}'",
                )
                _state.value = UpdateState.Error("Version check failed: unexpected version format")
                return@withContext
            }
            if (cmp <= 0) {
                logger.d("GithubUpdater", "Already up to date ($latestTag)")
                _state.value = UpdateState.UpToDate
                SettingsManager.lastUpdateCheckEpochDay = today
                return@withContext
            }
            _state.value = UpdateState.UpdateAvailable(
                version = latestTag,
                downloadUrl = packageAsset.browser_download_url,
                releaseNotes = release.body,
            )
            SettingsManager.lastUpdateCheckEpochDay = today
        } catch (e: Exception) {
            logger.e("GithubUpdater", "Update check failed", e)
            _state.value = UpdateState.Error("Check failed: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * Fetches the installer package and stops.
     */
    suspend fun download() = withContext(Dispatchers.IO) {
        val current = _state.value
        if (current !is UpdateState.UpdateAvailable) return@withContext
        cancelRequested = false
        _state.value = UpdateState.Downloading(0f)

        val ext = current.downloadUrl.substringAfterLast('.', "pkg")
        val dest = File(AppPaths.updatesDir, "Latch-${current.version}.$ext")
        try {
            val conn = open(URL(current.downloadUrl))
            if (conn.responseCode !in 200..299) {
                throw IOException("Download returned HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            var written = 0L
            var cancelled = false

            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(PIPE)
                    while (true) {
                        if (cancelRequested) {
                            cancelled = true
                            break
                        }
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        written += n
                        if (total > 0) {
                            _state.value =
                                UpdateState.Downloading((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }

            if (cancelled) {
                dest.delete()
                _state.value = current
                logger.d("GithubUpdater", "Download cancelled")
                return@withContext
            }

            if (total > 0 && written != total) {
                throw IOException("Incomplete download: got $written of $total bytes")
            }

            _state.value = UpdateState.Downloaded(current.version, dest.absolutePath)
            logger.d("GithubUpdater", "Downloaded update to ${dest.absolutePath}")
        } catch (e: Exception) {
            dest.delete()
            logger.e("GithubUpdater", "Download failed", e)
            _state.value = UpdateState.Error("Download failed: ${e.message ?: "Unknown error"}")
        }
    }

    fun cancelDownload() {
        cancelRequested = true
    }

    fun installAndExit(packagePath: String): Boolean = try {
        if (AppPaths.isWindows) {
            val script = writeRelaunchScript(packagePath)
            ProcessBuilder("cmd", "/c", script.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        } else {
            val script = writeLinuxUpdateScript(packagePath)
            ProcessBuilder("sh", script.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        }
    } catch (e: Exception) {
        logger.e("GithubUpdater", "Failed to launch installer", e)
        _state.value = UpdateState.Error("Failed to launch installer: ${e.message ?: "Unknown error"}")
        false
    }

    private fun writeLinuxUpdateScript(tarPath: String): File {
        val script = File.createTempFile("latch-update-", ".sh")
        script.setExecutable(true)
        val s = "$"
        script.writeText(
            """
            #!/usr/bin/env sh
            sleep 1
            TMP_TAR="$tarPath"
            if [ -w /opt/latch ] || [ ${s}(id -u) -eq 0 ]; then
                mkdir -p /opt/latch
                tar -xzf "${s}TMP_TAR" -C /opt/latch --strip-components=1 2>/dev/null || tar -xzf "${s}TMP_TAR" -C /opt/latch
                /opt/latch/bin/Latch &
            elif command -v pkexec >/dev/null 2>&1; then
                pkexec sh -c "mkdir -p /opt/latch && (tar -xzf \"${s}TMP_TAR\" -C /opt/latch --strip-components=1 2>/dev/null || tar -xzf \"${s}TMP_TAR\" -C /opt/latch)"
                /opt/latch/bin/Latch &
            else
                LOCAL_OPT="${s}HOME/.local/share/latch"
                mkdir -p "${s}LOCAL_OPT"
                tar -xzf "${s}TMP_TAR" -C "${s}LOCAL_OPT" --strip-components=1 2>/dev/null || tar -xzf "${s}TMP_TAR" -C "${s}LOCAL_OPT"
                "${s}LOCAL_OPT/bin/Latch" &
            fi
            """.trimIndent()
        )
        return script
    }

    private fun writeRelaunchScript(msiPath: String): File {
        val exePath = InstalledBuild.path
        val pid = ProcessHandle.current().pid()
        val script = File.createTempFile("latch-relaunch-", ".cmd")
        script.writeText(
            buildString {
                appendLine("@echo off")

                // Wait for *this* process to actually be gone before handing the
                // MSI to msiexec. The caller exits immediately after launching
                // this script, but "launched" is not "exited": if msiexec gets
                // there first it finds Latch.exe still locked, and under /qn the
                // user sees no error explaining why -- just a failed upgrade, or
                // a silently scheduled reboot.
                //
                // Polling the PID rather than sleeping a fixed couple of seconds:
                // a blind sleep is simultaneously too long on a fast exit and too
                // short on a slow one. tasklist's /fi filter means the output is
                // either the one process or an "INFO: No tasks..." line, so find
                // failing to match is exactly the "it's gone" signal.
                appendLine("set PID=$pid")
                appendLine("set /a tries=0")
                appendLine(":waitloop")
                // Absolute System32 paths, not bare names: a user with Git for
                // Windows (or any MSYS/Cygwin toolchain) on PATH resolves `find`
                // to the Unix one, which does not understand these arguments and
                // exits non-zero -- indistinguishable here from "the process is
                // gone", so the wait would silently skip and the race would be
                // back with no visible symptom.
                appendLine(
                    "%SystemRoot%\\System32\\tasklist.exe /fi \"PID eq %PID%\" /nh 2>nul | " +
                        "%SystemRoot%\\System32\\find.exe \"%PID%\" >nul"
                )
                appendLine("if errorlevel 1 goto ready")
                appendLine("set /a tries+=1")
                // ~30s ceiling, then install anyway: a process wedged this long
                // is not going to exit, and failing the upgrade outright is worse
                // than attempting it and letting msiexec report the locked file.
                appendLine("if %tries% geq 30 goto ready")
                // ping, not timeout: timeout aborts with "Input redirection is
                // not supported" when stdin isn't a console, which is exactly how
                // this script gets launched.
                appendLine("ping -n 2 127.0.0.1 >nul")
                appendLine("goto waitloop")
                appendLine(":ready")

                appendLine("msiexec /i \"$msiPath\" /qn /norestart")

                if (exePath != null) {
                    // Never `start` a path that isn't there. The old code
                    // relaunched unconditionally on the assumption that a failed
                    // upgrade leaves the previous version in place -- which is
                    // false for a major upgrade: RemoveExistingProducts takes the
                    // old product out first, so a failure downstream of that
                    // leaves *nothing* installed. What the user actually saw was
                    // "Windows cannot find ...\Latch.exe", which says nothing
                    // about the install having failed.
                    appendLine("if exist \"$exePath\" goto relaunch")
                    // Nothing installed and /qn ate the reason. Re-run the
                    // installer with a basic UI so the real error is on screen
                    // and the user has a route back to a working app, instead of
                    // being left with no Latch and no explanation.
                    appendLine("msiexec /i \"$msiPath\" /qb")
                    appendLine("if not exist \"$exePath\" goto cleanup")
                    appendLine(":relaunch")
                    appendLine("start \"\" \"$exePath\"")
                    appendLine(":cleanup")
                }
                appendLine("del \"%~f0\"")
            },
        )
        return script
    }

    /**
     * Removes MSIs left by an earlier run -- a postponed install, or a download
     * whose install never happened. Startup is the only safe moment to do this:
     * at any other point a file here may be one msiexec is mid-way through
     * reading. Each one is ~50 MB, so leaving them to accumulate is not an option.
     */
    fun cleanStaleDownloads() {
        runCatching {
            AppPaths.updatesDir.listFiles()?.forEach { file ->
                if (file.isFile && file.delete()) {
                    logger.d("GithubUpdater", "Removed stale download ${file.name}")
                }
            }
        }
    }

    fun dismissUpdate() {
        _state.value = when (val current = _state.value) {
            is UpdateState.UpdateAvailable -> UpdateState.Dismissed(current.version)
            // The MSI stays on disk and is swept on next launch; re-checking
            // offers it again, at the cost of downloading it a second time.
            is UpdateState.Downloaded -> UpdateState.Dismissed(current.version)
            else -> UpdateState.Idle
        }
    }

    private fun open(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun fetchLatestRelease(): GithubRelease {
        val conn = open(URL(GITHUB_API))
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val code = conn.responseCode
        if (code != 200) {
            val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw RuntimeException("GitHub API returned $code: $body")
        }
        return json.decodeFromString(conn.inputStream.bufferedReader().readText())
    }

    private fun compareVersions(a: String, b: String): Int? {
        val sa = parseSemver(a) ?: return null
        val sb = parseSemver(b) ?: return null
        return sa.compareTo(sb)
    }

    private fun parseSemver(v: String): Semver? {
        val parts = v.split('.')
        if (parts.size != 3) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patch = parts[2].toIntOrNull() ?: return null
        return Semver(major, minor, patch)
    }
}
