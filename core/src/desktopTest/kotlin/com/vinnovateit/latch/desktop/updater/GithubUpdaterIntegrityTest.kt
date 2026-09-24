package com.vinnovateit.latch.desktop.updater

import com.vinnovateit.latch.core.platform.BuildInfo
import com.vinnovateit.latch.core.platform.InMemoryKeyValueStore
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.updater.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val API = "https://api.github.com/repos/vinnovateit/latch/releases/latest"
private const val ASSET_URL = "https://github.com/vinnovateit/latch/releases/download/v1.4.2/Latch-Setup.msi"

/**
 * The Windows auto-update incident: Windows Installer reported "This
 * installation package could not be opened" after Latch's own updater ran.
 * These pin the invariant the fix rests on -- nothing reaches the installer
 * unless its bytes are exactly the release asset GitHub's API describes --
 * with a scripted HTTP layer, so no test here touches the network.
 */
class GithubUpdaterIntegrityTest {

    private lateinit var dir: File
    private val payload = Random(42).nextBytes(256 * 1024 + 17)
    private val payloadSha = sha(payload)
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val launched: MutableList<File> = Collections.synchronizedList(mutableListOf())
    private val requests = AtomicInteger()

    // What the fake GitHub serves. Each test adjusts these.
    private var assetSize: Long = payload.size.toLong()
    private var assetDigest: String? = "sha256:$payloadSha"
    private var assetUrl = ASSET_URL
    private var assetResponse: () -> UpdateHttpResponse = { ok(payload) }

    private val logger = object : Logger {
        override fun d(tag: String, message: String) { logs += "D $message" }
        override fun w(tag: String, message: String) { logs += "W $message" }
        override fun e(tag: String, message: String, throwable: Throwable?) { logs += "E $message" }
    }

    private val http = UpdateHttp { url, _ ->
        when (url) {
            API -> ok(releaseJson().toByteArray())
            assetUrl -> { requests.incrementAndGet(); assetResponse() }
            else -> fail("unexpected request to $url")
        }
    }

    private var updatesDir: () -> File = { dir }

    private val updater by lazy {
        GithubUpdater(
            buildInfo = object : BuildInfo {
                override val versionName = "1.4.1"
                override val isDebug = false
                override val isInstalled = true
            },
            logger = logger,
            http = http,
            updatesDir = { updatesDir() },
            isWindows = true,
            installerLauncher = { launched += it },
        )
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("latch-updater-test").toFile()
        SettingsManager.initialize(InMemoryKeyValueStore())
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    // --- 1. The good path ---------------------------------------------------

    @Test
    fun correctPayloadIsFinalizedVerifiedAndInstallable() = runBlocking<Unit> {
        val downloaded = checkAndDownload()

        assertIs<UpdateState.Downloaded>(downloaded)
        val msi = File(downloaded.filePath)
        assertEquals("Latch-1.4.2.msi", msi.name)
        assertContentEquals(payload, msi.readBytes())
        assertEquals(listOf(msi.name), dir.list()!!.toList(), "no .part may survive a success")

        assertTrue(updater.installAndExit(downloaded.filePath))
        assertEquals(listOf(msi.absoluteFile), launched)
    }

    @Test
    fun responseWithoutContentLengthIsAcceptedWhenTheAssetMatches() = runBlocking<Unit> {
        assetResponse = { ok(payload, contentLength = -1) }
        assertIs<UpdateState.Downloaded>(checkAndDownload())
    }

    // --- 2. The incident ----------------------------------------------------

    @Test
    fun captivePortalHtmlWithHttp200IsRejectedBeforeInstallerLaunch() = runBlocking<Unit> {
        // The v1.4.2 asset as the API describes it...
        assetSize = 78_930_816
        assetDigest = "sha256:d2d0698af5c2aa1c9fe57fb763b26923a46bff8706bc0a493a4440b0f9944cda"
        // ...and what a portal or proxy answers instead: a complete, honest
        // HTTP 200 whose Content-Length is exactly its own HTML body.
        val html = "<html><head><title>Login</title></head><body>captive portal</body></html>".toByteArray()
        assetResponse = { ok(html, contentLength = html.size.toLong(), contentType = "text/html") }

        // The only check the old updater made was written == Content-Length
        // when Content-Length > 0. This response passes it.
        val total = html.size.toLong()
        val written = html.size.toLong()
        assertFalse(total > 0 && written != total, "the pre-fix check accepted this response")

        val state = checkAndDownload()

        assertEquals(UpdateState.Error(INTEGRITY_FAILURE_MESSAGE), state)
        assertNoPackageFiles()
        assertFalse(updater.installAndExit(File(dir, "Latch-1.4.2.msi").path))
        assertTrue(launched.isEmpty(), "msiexec must never see the portal page")
    }

    @Test
    fun captivePortalHtmlWithoutContentLengthIsRejected() = runBlocking<Unit> {
        assetResponse = { ok("<html>portal</html>".toByteArray(), contentLength = -1) }
        assertEquals(UpdateState.Error(INTEGRITY_FAILURE_MESSAGE), checkAndDownload())
        assertNoPackageFiles()
    }

    // --- 3-5. Length ----------------------------------------------------------

    @Test
    fun truncatedBodyIsRejected() = runBlocking<Unit> {
        // Content-Length promises the whole asset; the connection ends early.
        assetResponse = { ok(payload.copyOf(payload.size / 2), contentLength = payload.size.toLong()) }
        assertEquals(UpdateState.Error(INTEGRITY_FAILURE_MESSAGE), checkAndDownload())
        assertNoPackageFiles()
        assertTrue(logs.any { "got ${payload.size / 2} bytes, expected ${payload.size}" in it }, logs.toString())
    }

    @Test
    fun missingContentLengthStillEnforcesTheAssetSize() = runBlocking<Unit> {
        assetResponse = { ok(payload.copyOf(payload.size - 1), contentLength = -1) }
        assertEquals(UpdateState.Error(INTEGRITY_FAILURE_MESSAGE), checkAndDownload())
        assertNoPackageFiles()
    }

    @Test
    fun bodyLongerThanTheAssetIsRejected() = runBlocking<Unit> {
        assetResponse = { ok(payload + byteArrayOf(0), contentLength = -1) }
        assertEquals(UpdateState.Error(INTEGRITY_FAILURE_MESSAGE), checkAndDownload())
        assertNoPackageFiles()
    }

    @Test
    fun contentLengthDisagreeingWithTheAssetIsRejectedWithoutReadingTheBody() = runBlocking<Unit> {
        assetResponse = {
            UpdateHttpResponse(200, payload.size + 1L, "application/octet-stream", { fail("body must not be read") })
        }
        assertEquals(UpdateState.Error(INTEGRITY_FAILURE_MESSAGE), checkAndDownload())
        assertNoPackageFiles()
        assertTrue(logs.any { "Content-Length ${payload.size + 1}" in it }, logs.toString())
    }

    @Test
    fun httpFailureIsReportedAsSuchAndNothingIsStaged() = runBlocking<Unit> {
        assetResponse = { UpdateHttpResponse(404, 9, "text/plain", { ByteArrayInputStream("Not Found".toByteArray()) }) }
        assertEquals(UpdateState.Error("Download failed: Download returned HTTP 404"), checkAndDownload())
        assertNoPackageFiles()
    }

    @Test
    fun unfollowedRedirectIsNotTreatedAsTheAsset() = runBlocking<Unit> {
        // HttpURLConnection will not follow https -> http; the 302 itself
        // comes back, with a small HTML body.
        assetResponse = { UpdateHttpResponse(302, 5, "text/html", { ByteArrayInputStream("moved".toByteArray()) }) }
        assertIs<UpdateState.Error>(checkAndDownload())
        assertNoPackageFiles()
    }

    // --- 6-8. Digest ----------------------------------------------------------

    @Test
    fun rightSizeWrongDigestIsRejected() = runBlocking<Unit> {
        val tampered = payload.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assetResponse = { ok(tampered) }
        assertEquals(UpdateState.Error(INTEGRITY_FAILURE_MESSAGE), checkAndDownload())
        assertNoPackageFiles()
        assertTrue(logs.any { "sha256 mismatch: got ${sha(tampered)}, expected $payloadSha" in it }, logs.toString())
    }

    @Test
    fun malformedDigestMetadataIsRefusedBeforeAnythingIsOffered() = runBlocking<Unit> {
        for (bad in listOf(
            "sha1:${payloadSha.take(40)}",
            "sha256:${payloadSha.dropLast(1)}",
            "sha256:${payloadSha.dropLast(1)}g",
            payloadSha,
            "",
        )) {
            assetDigest = bad
            updater.check(force = true)
            assertEquals(
                UpdateState.Error("Update check failed: release metadata could not be verified"),
                updater.state.value,
                "digest '$bad'",
            )
            updater.download()
            assertEquals(0, requests.get(), "no download may start for digest '$bad'")
        }
        assertTrue(logs.any { "malformed metadata (unsupported digest format)" in it }, logs.toString())
    }

    @Test
    fun missingDigestMetadataIsRefused() = runBlocking<Unit> {
        // Policy: no trusted digest, no update. Size alone cannot tell a
        // corrupt package from the real one.
        assetDigest = null
        updater.check(force = true)
        assertEquals(UpdateState.Error("Update check failed: release metadata could not be verified"), updater.state.value)
        updater.download()
        assertEquals(0, requests.get())
        assertTrue(logs.any { "malformed metadata (no digest)" in it }, logs.toString())
    }

    @Test
    fun uppercaseDigestMetadataStillMatchesExactly() = runBlocking<Unit> {
        assetDigest = "sha256:${payloadSha.uppercase()}"
        assertIs<UpdateState.Downloaded>(checkAndDownload())
    }

    @Test
    fun assetOutsideThisRepositorysReleasesIsRefused() = runBlocking<Unit> {
        assetUrl = "https://example.com/Latch-Setup.msi"
        updater.check(force = true)
        assertIs<UpdateState.Error>(updater.state.value)
        assertTrue(logs.any { "download URL outside" in it }, logs.toString())
    }

    // --- 9-11. Cancellation, leftovers, supersession --------------------------

    @Test
    fun cancelledDownloadLeavesNothingBehind() = runBlocking<Unit> {
        assetResponse = {
            ok(payload, body = object : InputStream() {
                val inner = ByteArrayInputStream(payload)
                var reads = 0
                override fun read() = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (++reads == 2) updater.cancelDownload()
                    return inner.read(b, off, len)
                }
            })
        }
        val state = checkAndDownload()

        assertIs<UpdateState.UpdateAvailable>(state, "cancel returns to the offer")
        assertNoPackageFiles()
        assertTrue(launched.isEmpty())
    }

    @Test
    fun stalePartFromAnEarlierRunIsReplaced() = runBlocking<Unit> {
        File(dir, "Latch-1.4.2.msi.part").writeText("left over by a killed download")
        val state = checkAndDownload()
        assertIs<UpdateState.Downloaded>(state)
        assertContentEquals(payload, File(state.filePath).readBytes())
        assertFalse(File(dir, "Latch-1.4.2.msi.part").exists())
    }

    @Test
    fun earlierValidPackageIsInvalidatedByAFailedNewAttempt() = runBlocking<Unit> {
        val first = checkAndDownload()
        assertIs<UpdateState.Downloaded>(first)

        // The same version is offered again and this time the transfer is bad.
        assetResponse = { ok(payload.copyOf(10)) }
        val second = checkAndDownload()

        assertEquals(UpdateState.Error(INTEGRITY_FAILURE_MESSAGE), second)
        // The earlier copy does not linger to be installed as if it were the
        // result of this attempt.
        assertNoPackageFiles()
        assertFalse(updater.installAndExit(first.filePath))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun leftoverFinalNameFromAnEarlierRunIsNeverTrusted() = runBlocking<Unit> {
        // e.g. from before a restart; verification lives in memory only.
        File(dir, "Latch-1.4.2.msi").writeBytes(payload)
        updater.check(force = true)
        assertFalse(updater.installAndExit(File(dir, "Latch-1.4.2.msi").path))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun retryAfterAFailedDownloadStartsAgain() = runBlocking<Unit> {
        assetResponse = { ok(payload.copyOf(10)) }
        assertIs<UpdateState.Error>(checkAndDownload())

        assetResponse = { ok(payload) }
        updater.download()
        assertIs<UpdateState.Downloaded>(updater.state.value)
        assertEquals(2, requests.get())
    }

    @Test
    fun overlappingDownloadRequestsFetchOnce() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        assetResponse = {
            ok(payload, body = object : InputStream() {
                val inner = ByteArrayInputStream(payload)
                override fun read() = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    return inner.read(b, off, len)
                }
            })
        }
        updater.check(force = true)
        val first = async(Dispatchers.IO) { updater.download() }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        updater.download() // second tap while the first is in flight
        release.countDown()
        first.await()

        assertEquals(1, requests.get())
        assertIs<UpdateState.Downloaded>(updater.state.value)
    }

    // --- 12-14. Install eligibility and write failures ----------------------

    @Test
    fun arbitraryMsiPathCannotReachTheInstaller() = runBlocking<Unit> {
        val planted = File(dir, "Latch-9.9.9.msi").apply { writeBytes(payload) }
        assertFalse(updater.installAndExit(planted.path))

        // Not even after a real download: only the file it verified qualifies.
        val downloaded = checkAndDownload()
        assertIs<UpdateState.Downloaded>(downloaded)
        assertFalse(updater.installAndExit(planted.path))
        assertEquals(UpdateState.Error(UNVERIFIED_PACKAGE_MESSAGE), updater.state.value)
        assertTrue(launched.isEmpty())
    }

    @Test
    fun packageTamperedAfterVerificationIsCaughtAtInstall() = runBlocking<Unit> {
        val downloaded = checkAndDownload()
        assertIs<UpdateState.Downloaded>(downloaded)
        val msi = File(downloaded.filePath)
        msi.writeBytes(payload.copyOf().also { it[0] = (it[0] + 1).toByte() })

        assertFalse(updater.installAndExit(downloaded.filePath))
        assertEquals(UpdateState.Error(UNVERIFIED_PACKAGE_MESSAGE), updater.state.value)
        assertTrue(launched.isEmpty())
        assertFalse(msi.exists(), "a package that failed re-verification is discarded")
    }

    @Test
    fun packageTruncatedAfterVerificationIsCaughtAtInstall() = runBlocking<Unit> {
        val downloaded = checkAndDownload()
        assertIs<UpdateState.Downloaded>(downloaded)
        File(downloaded.filePath).writeBytes(payload.copyOf(100))
        assertFalse(updater.installAndExit(downloaded.filePath))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun destinationWriteFailureIsAnErrorNotADownload() = runBlocking<Unit> {
        // A "directory" that is really a file: nothing can be created in it.
        val blocker = File(dir, "not-a-directory").apply { writeText("x") }
        updatesDir = { blocker }
        val state = checkAndDownload()

        assertIs<UpdateState.Error>(state)
        assertTrue(state.message.startsWith("Download failed:"), state.message)
        assertTrue(launched.isEmpty())
    }

    // --- 15. Hashing ------------------------------------------------------------

    @Test
    fun sha256OfKnownFixtures() {
        val empty = File(dir, "empty").apply { writeBytes(ByteArray(0)) }
        val abc = File(dir, "abc").apply { writeText("abc") }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(empty))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(abc))
        assertEquals(payloadSha, sha256Hex(File(dir, "p").apply { writeBytes(payload) }))
    }

    @Test
    fun digestParsingAcceptsOnlyWellFormedSha256() {
        val hex = "a".repeat(64)
        assertEquals(hex, parseSha256Digest("sha256:$hex"))
        assertEquals(hex, parseSha256Digest("sha256:${hex.uppercase()}"))
        assertNull(parseSha256Digest(null))
        assertNull(parseSha256Digest(hex))
        assertNull(parseSha256Digest("sha512:$hex$hex"))
        assertNull(parseSha256Digest("sha256:${hex}0"))
        assertFalse(digestsEqual(hex, "a".repeat(63) + "b"))
        assertFalse(digestsEqual(hex, "a".repeat(63)))
    }

    // --- Installer handoff and cleanup -------------------------------------------

    @Test
    fun relaunchScriptTakesItsPathsFromTheEnvironment() = runBlocking<Unit> {
        val downloaded = checkAndDownload()
        assertIs<UpdateState.Downloaded>(downloaded)
        val script = updater.relaunchScript(hasInstalledExe = true)
        val env = updater.relaunchEnvironment(File(downloaded.filePath))

        assertFalse(dir.absolutePath in script, "no path may be baked into the OEM-code-page script")
        assertTrue("msiexec.exe /i \"%LATCH_MSI%\" /qn /norestart /L*V \"%LATCH_MSI_LOG%\"" in script, script)
        assertTrue("if exist \"%LATCH_EXE%\" goto relaunch" in script, script)
        assertEquals(File(downloaded.filePath).absolutePath, env["LATCH_MSI"])
        assertEquals(ProcessHandle.current().pid().toString(), env["LATCH_PID"])
        assertTrue(env.getValue("LATCH_MSI_LOG").endsWith(".log"))
    }

    @Test
    fun startupCleanupRemovesPackagesButKeepsInstallerLogs() {
        File(dir, "Latch-1.4.2.msi").writeText("x")
        File(dir, "Latch-1.4.2.msi.part").writeText("x")
        File(dir, "msiexec-install.log").writeText("x")
        updater.cleanStaleDownloads()
        assertEquals(listOf("msiexec-install.log"), dir.list()!!.toList())
    }

    // --- helpers ----------------------------------------------------------------

    private suspend fun checkAndDownload(): UpdateState {
        updater.check(force = true)
        assertIs<UpdateState.UpdateAvailable>(updater.state.value, logs.toString())
        updater.download()
        return updater.state.value
    }

    private fun assertNoPackageFiles() {
        val left = dir.list()!!.filter { it.endsWith(".msi") || it.endsWith(".part") }
        assertTrue(left.isEmpty(), "left behind: $left")
    }

    private fun ok(
        bytes: ByteArray,
        contentLength: Long = bytes.size.toLong(),
        contentType: String = "application/octet-stream",
        body: InputStream = ByteArrayInputStream(bytes),
    ) = UpdateHttpResponse(200, contentLength, contentType, { body })

    private fun releaseJson(): String {
        val digest = assetDigest?.let { "\"digest\": \"$it\"," } ?: ""
        return """
            {
              "tag_name": "v1.4.2",
              "body": "notes",
              "assets": [
                {
                  "name": "latch-cli-1.4.2-windows-x64.zip",
                  "browser_download_url": "https://github.com/vinnovateit/latch/releases/download/v1.4.2/latch-cli-1.4.2-windows-x64.zip",
                  "size": 1,
                  "digest": "sha256:${"0".repeat(64)}"
                },
                {
                  "name": "Latch-Setup.msi",
                  "browser_download_url": "$assetUrl",
                  "size": $assetSize,
                  $digest
                  "content_type": "application/x-msdownload",
                  "state": "uploaded"
                }
              ]
            }
        """.trimIndent()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}
