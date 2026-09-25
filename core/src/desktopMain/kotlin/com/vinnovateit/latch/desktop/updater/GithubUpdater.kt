package com.vinnovateit.latch.desktop.updater

import com.vinnovateit.latch.core.platform.BuildInfo
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.desktop.AppPaths
import com.vinnovateit.latch.desktop.platform.InstalledBuild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException

private const val GITHUB_API = "https://api.github.com/repos/vinnovateit/latch/releases/latest"

// Every asset this updater will fetch must be served from this repository's
// own release downloads. The API response is what vouches for the asset's
// size and digest, so the URL it points at has to be the one it describes.
private const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/vinnovateit/latch/releases/download/"
/**
 * The Windows installer's release asset name, matched exactly.
 *
 * Deliberately not `Latch-*.msi`, the only shape updaters up to 1.4.2 offer:
 * they stage the package inside the install directory, which the upgrade then
 * deletes mid-install, leaving no Latch installed. Under this name those
 * updaters find no Windows package and stay on a working install; users on
 * them update once by hand. Must never start with "Latch-" again.
 */
internal const val WINDOWS_PACKAGE_ASSET = "LatchSetup.msi"
private const val PIPE = 32 * 1024
private const val TAG = "GithubUpdater"

private const val PART_SUFFIX = ".part"
private const val INSTALLER_LOG_SUFFIX = ".log"

internal const val INTEGRITY_FAILURE_MESSAGE =
    "Update download failed integrity verification. Please retry."
internal const val UNVERIFIED_PACKAGE_MESSAGE =
    "The downloaded update could not be verified. Check for updates again."

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
    // "sha256:<hex>" on current API responses. Nullable so a response without
    // it parses and is then refused explicitly, rather than failing the whole
    // check with a serialization error that says nothing useful.
    val digest: String? = null,
)

private data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {
    override fun compareTo(other: Semver): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
}

/** The update [check] found, with the asset metadata its download must match. */
private data class Offer(val version: String, val releaseNotes: String, val asset: TrustedAsset) {
    val available get() = UpdateState.UpdateAvailable(version, asset.url, releaseNotes)
}

/** A package whose bytes were proven to be [Offer.asset]. The only thing [GithubUpdater.installAndExit] runs. */
private data class VerifiedPackage(val file: File, val size: Long, val sha256: String)

/** Thrown out of the copy loop when the user cancels; never escapes [GithubUpdater.download]. */
private class DownloadCancelled : Exception()

class GithubUpdater internal constructor(
    private val buildInfo: BuildInfo,
    private val logger: Logger,
    private val http: UpdateHttp,
    private val updatesDir: () -> File,
    private val isWindows: Boolean,
    /** Replaces the real msiexec/tar handoff in tests; null means the real one. */
    private val installerLauncher: ((File) -> Unit)?,
) {
    constructor(buildInfo: BuildInfo, logger: Logger) : this(
        buildInfo = buildInfo,
        logger = logger,
        http = UrlConnectionUpdateHttp,
        updatesDir = { AppPaths.updatesDir },
        isWindows = AppPaths.isWindows,
        installerLauncher = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var offer: Offer? = null

    @Volatile
    private var verified: VerifiedPackage? = null

    /**
     * @param force Bypasses the once-a-day gate. The manual "Check for Updates"
     * button passes true; the silent startup check leaves it false so opening
     * the app several times in a day doesn't re-hit the API on every launch.
     * Persisted (not just in-memory) since [GithubUpdater] is recreated fresh
     * each process start.
     */
    suspend fun check(force: Boolean = false) = withContext(Dispatchers.IO) {
        // A check would overwrite the progress state the running download
        // reports through, and swap the offer it is verifying against.
        if (_state.value is UpdateState.Downloading) return@withContext
        val today = LocalDate.now(ZoneOffset.UTC).toEpochDay()
        if (!force && SettingsManager.lastUpdateCheckEpochDay == today) {
            logger.d(TAG, "Skipping check — already checked today")
            return@withContext
        }
        _state.value = UpdateState.Checking
        try {
            val release = fetchLatestRelease()
            val packageAsset = release.assets.find { asset ->
                if (isWindows) {
                    asset.name == WINDOWS_PACKAGE_ASSET
                } else {
                    asset.name.contains("Latch", ignoreCase = true) &&
                        (asset.name.endsWith(".deb") || asset.name.endsWith(".AppImage") || asset.name.endsWith(".tar.gz"))
                }
            }
            if (packageAsset == null) {
                logger.d(TAG, "No compatible release asset in latest release")
                _state.value = UpdateState.UpToDate
                SettingsManager.lastUpdateCheckEpochDay = today
                return@withContext
            }
            val latestTag = release.tag_name.removePrefix("v")
            val cmp = compareVersions(latestTag, buildInfo.versionName)
            if (cmp == null) {
                logger.e(
                    TAG,
                    "Could not compare versions: latest='$latestTag', current='${buildInfo.versionName}'",
                )
                _state.value = UpdateState.Error("Version check failed: unexpected version format")
                return@withContext
            }
            if (cmp <= 0) {
                logger.d(TAG, "Already up to date ($latestTag)")
                _state.value = UpdateState.UpToDate
                SettingsManager.lastUpdateCheckEpochDay = today
                return@withContext
            }
            // Refused here, before anything is offered: an update that can
            // never pass verification is not one to show the user.
            val trusted = trustedAsset(packageAsset)
            if (trusted == null) {
                _state.value = UpdateState.Error("Update check failed: release metadata could not be verified")
                return@withContext
            }
            val found = Offer(latestTag, release.body, trusted)
            offer = found
            _state.value = found.available
            SettingsManager.lastUpdateCheckEpochDay = today
        } catch (e: Exception) {
            logger.e(TAG, "Update check failed", e)
            _state.value = UpdateState.Error("Check failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun trustedAsset(asset: GithubAsset): TrustedAsset? {
        val sha256 = parseSha256Digest(asset.digest)
        val problem = when {
            asset.digest == null -> "no digest"
            sha256 == null -> "unsupported digest format"
            asset.size <= 0 -> "invalid size ${asset.size}"
            !asset.browser_download_url.startsWith(RELEASE_DOWNLOAD_PREFIX) -> "download URL outside this repository's releases"
            else -> null
        }
        if (problem != null) {
            logger.e(TAG, "Refusing release asset ${asset.name}: malformed metadata ($problem)")
            return null
        }
        return TrustedAsset(asset.name, asset.browser_download_url, asset.size, sha256!!)
    }

    /**
     * Fetches the installer package and stops.
     *
     * The package is written to `<name>.part` and only renamed to its final
     * name once it is exactly the asset the release metadata describes: the
     * same size, and the same SHA-256. Nothing else ever becomes
     * [UpdateState.Downloaded], so nothing else can reach [installAndExit].
     *
     * Also the retry path: a failed attempt leaves the offer in place, so the
     * Retry button's call here starts over from [UpdateState.Error].
     */
    suspend fun download() = withContext(Dispatchers.IO) {
        val current = _state.value
        val offer = offer ?: return@withContext
        if (current !is UpdateState.UpdateAvailable && current !is UpdateState.Error) return@withContext
        // Claimed atomically: two taps landing in the same frame would
        // otherwise both pass the check above and write the same file.
        if (!_state.compareAndSet(current, UpdateState.Downloading(0f))) return@withContext
        cancelRequested = false

        val asset = offer.asset
        val ext = asset.name.substringAfterLast('.', "pkg")
        val dir = updatesDir()
        val dest = File(dir, "Latch-${offer.version}.$ext")
        val part = File(dir, dest.name + PART_SUFFIX)
        logger.d(
            TAG,
            "Downloading ${asset.name} for ${offer.version}: expecting ${asset.size} bytes, " +
                "sha256 ${asset.sha256}, to ${dest.name}",
        )

        try {
            // A new attempt supersedes whatever an earlier one produced. An
            // older copy is never left behind to be installed in its place.
            verified = null
            Files.deleteIfExists(dest.toPath())
            Files.deleteIfExists(part.toPath())

            val sha256 = fetchVerified(asset, part)

            // Only now does the final name exist, and it exists complete.
            moveIntoPlace(part, dest)
            verified = VerifiedPackage(dest.absoluteFile, asset.size, sha256)
            logger.d(TAG, "Verified ${asset.name} (${asset.size} bytes, sha256 match); ready at ${dest.name}")
            _state.value = UpdateState.Downloaded(offer.version, dest.absolutePath)
        } catch (e: DownloadCancelled) {
            part.delete()
            logger.d(TAG, "Download cancelled")
            _state.value = offer.available
        } catch (e: CancellationException) {
            part.delete()
            _state.value = offer.available
            throw e
        } catch (e: UpdateIntegrityException) {
            part.delete()
            dest.delete()
            logger.e(TAG, "Rejected ${asset.name} for ${offer.version}: ${e.message}")
            _state.value = UpdateState.Error(INTEGRITY_FAILURE_MESSAGE)
        } catch (e: Exception) {
            part.delete()
            dest.delete()
            logger.e(TAG, "Download of ${asset.name} failed", e)
            _state.value = UpdateState.Error("Download failed: ${e.message ?: "Unknown error"}")
        }
    }

    /** Streams [asset] into [part], hashing as it goes; returns the verified SHA-256. */
    private suspend fun fetchVerified(asset: TrustedAsset, part: File): String {
        http.get(asset.url, accept = null).use { response ->
            if (response.code != 200) throw IOException("Download returned HTTP ${response.code}")
            logger.d(
                TAG,
                "HTTP 200, Content-Length ${response.contentLength}, Content-Type ${response.contentType ?: "none"}",
            )
            // The declared length is the server's claim about *this* body, not
            // about the asset: a captive portal's HTML page has a perfectly
            // accurate one. It can only ever disqualify a response.
            if (response.contentLength >= 0 && response.contentLength != asset.size) {
                throw UpdateIntegrityException(
                    "size mismatch: Content-Length ${response.contentLength}, expected ${asset.size}",
                )
            }

            val md = MessageDigest.getInstance("SHA-256")
            var written = 0L
            if (!part.createNewFile()) throw IOException("Could not create ${part.name}")
            FileOutputStream(part).use { output ->
                val input = response.body
                val buf = ByteArray(PIPE)
                while (true) {
                    if (cancelRequested) throw DownloadCancelled()
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n <= 0) break
                    written += n
                    if (written > asset.size) {
                        throw UpdateIntegrityException("size mismatch: more than the expected ${asset.size} bytes")
                    }
                    md.update(buf, 0, n)
                    output.write(buf, 0, n)
                    _state.value = UpdateState.Downloading((written.toFloat() / asset.size).coerceIn(0f, 1f))
                }
                // On disk before the rename makes it installable, not just
                // handed to the OS: msiexec reads it after this process exits.
                output.flush()
                output.fd.sync()
            }

            if (written != asset.size) {
                throw UpdateIntegrityException("size mismatch: got $written bytes, expected ${asset.size}")
            }
            val actual = md.digest().toHex()
            if (!digestsEqual(asset.sha256, actual)) {
                throw UpdateIntegrityException("sha256 mismatch: got $actual, expected ${asset.sha256}")
            }
            return actual
        }
    }

    private fun moveIntoPlace(part: File, dest: File) {
        try {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun cancelDownload() {
        cancelRequested = true
    }

    /**
     * Hands [packagePath] to the installer, but only if it is the package
     * [download] verified, and only if it still is: the file is hashed again
     * here, so one changed or replaced since the download is refused too.
     * The installer is given the verified file, never the caller's string.
     */
    fun installAndExit(packagePath: String): Boolean {
        val pkg = verified
        if (pkg == null || !sameFile(pkg.file, File(packagePath))) {
            logger.e(TAG, "Refusing to install ${File(packagePath).name}: not a package this updater verified")
            _state.value = UpdateState.Error(UNVERIFIED_PACKAGE_MESSAGE)
            return false
        }
        val stillValid = runCatching {
            pkg.file.isFile && pkg.file.length() == pkg.size && digestsEqual(pkg.sha256, sha256Hex(pkg.file))
        }.getOrDefault(false)
        if (!stillValid) {
            logger.e(TAG, "Refusing to install ${pkg.file.name}: it changed after it was verified")
            verified = null
            pkg.file.delete()
            _state.value = UpdateState.Error(UNVERIFIED_PACKAGE_MESSAGE)
            return false
        }
        return try {
            (installerLauncher ?: ::launchInstaller)(pkg.file)
            logger.d(TAG, "Launched installer for ${pkg.file.name}")
            true
        } catch (e: Exception) {
            logger.e(TAG, "Failed to launch installer", e)
            _state.value = UpdateState.Error("Failed to launch installer: ${e.message ?: "Unknown error"}")
            false
        }
    }

    private fun sameFile(a: File, b: File): Boolean =
        a.toPath().toAbsolutePath().normalize() == b.toPath().toAbsolutePath().normalize()

    private fun launchInstaller(packageFile: File) {
        if (isWindows) {
            val script = writeRelaunchScript()
            val builder = ProcessBuilder("cmd", "/c", script.absolutePath)
            relaunchEnvironment(packageFile).forEach { (k, v) -> builder.environment()[k] = v }
            builder
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } else {
            val script = writeLinuxUpdateScript(packageFile.absolutePath)
            ProcessBuilder("sh", script.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
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

    /**
     * The paths the relaunch script works on. Passed as environment variables
     * rather than written into the script: cmd.exe parses a .cmd file in the
     * console's OEM code page, so a profile path with any non-ASCII character
     * came out mangled -- msiexec was told to open a file that does not exist
     * ("This installation package could not be opened"), and the Latch.exe
     * check failed the same way. A `%` in a path was expanded as a variable.
     * The environment block is UTF-16 end to end, and a variable's expanded
     * value is not parsed again.
     */
    internal fun relaunchEnvironment(packageFile: File): Map<String, String> = buildMap {
        put("LATCH_PID", ProcessHandle.current().pid().toString())
        put("LATCH_MSI", packageFile.absolutePath)
        // Next to the package, outside the install directory, and kept by
        // cleanStaleDownloads: /qn reports nothing, so this is the only
        // record of why an upgrade failed.
        put("LATCH_MSI_LOG", File(packageFile.absoluteFile.parentFile, "msiexec-install$INSTALLER_LOG_SUFFIX").absolutePath)
        put("LATCH_MSI_UI_LOG", File(packageFile.absoluteFile.parentFile, "msiexec-install-ui$INSTALLER_LOG_SUFFIX").absolutePath)
        InstalledBuild.path?.let { put("LATCH_EXE", it) }
    }

    private fun writeRelaunchScript(): File {
        val script = File.createTempFile("latch-relaunch-", ".cmd")
        script.writeText(relaunchScript(hasInstalledExe = InstalledBuild.path != null))
        return script
    }

    internal fun relaunchScript(hasInstalledExe: Boolean): String = buildString {
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
        appendLine("set /a tries=0")
        appendLine(":waitloop")
        // Absolute System32 paths, not bare names: a user with Git for
        // Windows (or any MSYS/Cygwin toolchain) on PATH resolves `find`
        // to the Unix one, which does not understand these arguments and
        // exits non-zero -- indistinguishable here from "the process is
        // gone", so the wait would silently skip and the race would be
        // back with no visible symptom.
        appendLine(
            "%SystemRoot%\\System32\\tasklist.exe /fi \"PID eq %LATCH_PID%\" /nh 2>nul | " +
                "%SystemRoot%\\System32\\find.exe \"%LATCH_PID%\" >nul"
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

        appendLine("%SystemRoot%\\System32\\msiexec.exe /i \"%LATCH_MSI%\" /qn /norestart /L*V \"%LATCH_MSI_LOG%\"")

        if (hasInstalledExe) {
            // Never `start` a path that isn't there. The old code
            // relaunched unconditionally on the assumption that a failed
            // upgrade leaves the previous version in place -- which is
            // false for a major upgrade: RemoveExistingProducts takes the
            // old product out first, so a failure downstream of that
            // leaves *nothing* installed. What the user actually saw was
            // "Windows cannot find ...\Latch.exe", which says nothing
            // about the install having failed.
            appendLine("if exist \"%LATCH_EXE%\" goto relaunch")
            // Nothing installed and /qn ate the reason. Re-run the
            // installer with a basic UI so the real error is on screen
            // and the user has a route back to a working app, instead of
            // being left with no Latch and no explanation.
            appendLine("%SystemRoot%\\System32\\msiexec.exe /i \"%LATCH_MSI%\" /qb /L*V \"%LATCH_MSI_UI_LOG%\"")
            appendLine("if not exist \"%LATCH_EXE%\" goto cleanup")
            appendLine(":relaunch")
            appendLine("start \"\" \"%LATCH_EXE%\"")
            appendLine(":cleanup")
        }
        appendLine("del \"%~f0\"")
    }

    /**
     * Removes packages left by an earlier run -- a postponed install, a
     * download whose install never happened, or a `.part` from an attempt
     * that was killed. Each one is ~80 MB, so leaving them to accumulate is
     * not an option. Installer logs are kept: they are small, overwritten by
     * the next attempt, and the only record of a failed silent upgrade.
     *
     * Startup is the only safe moment to do this: at any other point a file
     * here may be one msiexec is mid-way through reading. At startup nothing
     * of this process's is in flight -- [download] cannot start before this
     * returns -- and the msiexec a previous run launched only relaunches Latch
     * once it has finished with the package. A copy the user starts by hand
     * mid-upgrade is the one overlap left; Windows refuses to delete a package
     * msiexec holds open, and the failure is ignored here.
     */
    fun cleanStaleDownloads() {
        runCatching {
            updatesDir().listFiles()?.forEach { file ->
                if (file.isFile && !file.name.endsWith(INSTALLER_LOG_SUFFIX) && file.delete()) {
                    logger.d(TAG, "Removed stale download ${file.name}")
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

    private fun fetchLatestRelease(): GithubRelease {
        http.get(GITHUB_API, accept = "application/vnd.github+json").use { response ->
            val code = response.code
            if (code == 403 || code == 429) {
                logger.d(TAG, "GitHub API rate limit exceeded ($code)")
                throw IOException("GitHub API rate limit exceeded ($code)")
            }
            // The body is not echoed: on a captive portal it is the portal's
            // page, which has no place in the log or on screen.
            if (code != 200) throw IOException("GitHub API returned $code")
            return json.decodeFromString(response.body.bufferedReader().readText())
        }
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
