package com.vinnovateit.latch.desktop.updater

import com.vinnovateit.latch.core.platform.BuildInfo
import com.vinnovateit.latch.core.platform.InMemoryKeyValueStore
import com.vinnovateit.latch.core.platform.NoOpLogger
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.desktop.platform.DesktopOs
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val LATEST = "https://api.github.com/repos/vinnovateit/latch/releases/latest"
private const val RELEASE_PAGE = "https://github.com/vinnovateit/latch/releases/tag/v1.5.0"

/**
 * macOS gets update discovery only: the offer opens the release page, and
 * nothing is ever downloaded or run by the app -- in particular not the
 * Linux tarball or Windows MSI handoff the other branches use.
 */
class GithubUpdaterMacTest {

    private lateinit var dir: File
    private val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val launched: MutableList<File> = Collections.synchronizedList(mutableListOf())

    private var tag = "v1.5.0"
    private var htmlUrl: String? = RELEASE_PAGE
    private var assets = listOf("Latch-1.5.0.dmg", "LatchSetup.msi", "latch-1.5.0-linux-x64.tar.gz")

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("latch-updater-mac").toFile()
        SettingsManager.initialize(InMemoryKeyValueStore())
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun releaseJson(): String {
        val assetJson = assets.joinToString(",") { name ->
            """{"name": "$name", "browser_download_url": "https://github.com/vinnovateit/latch/releases/download/$tag/$name",
               "size": 1024, "digest": "sha256:${"a".repeat(64)}"}"""
        }
        val page = htmlUrl?.let { "\"html_url\": \"$it\"," } ?: ""
        return """{"tag_name": "$tag", $page "body": "notes", "assets": [$assetJson]}"""
    }

    private val updater by lazy {
        GithubUpdater(
            buildInfo = object : BuildInfo {
                override val versionName = "1.4.3"
                override val isDebug = false
                override val isInstalled = true
            },
            logger = NoOpLogger,
            http = UpdateHttp { url, _ ->
                requested += url
                val body = if (url == LATEST) releaseJson().toByteArray() else ByteArray(1024)
                UpdateHttpResponse(200, body.size.toLong(), "application/json", { ByteArrayInputStream(body) })
            },
            updatesDir = { dir },
            os = DesktopOs.MACOS,
            installerLauncher = { launched += it },
        )
    }

    @Test
    fun `a newer release with a macOS build is offered for manual install from its release page`() = runBlocking<Unit> {
        updater.check(force = true)

        val offer = assertIs<UpdateState.UpdateAvailable>(updater.state.value)
        assertEquals("1.5.0", offer.version)
        assertTrue(offer.manualInstall)
        assertEquals(RELEASE_PAGE, offer.downloadUrl)
    }

    @Test
    fun `nothing is ever downloaded or installed on macOS`() = runBlocking<Unit> {
        updater.check(force = true)
        updater.download()

        assertEquals(listOf(LATEST), requested, "only the release metadata may be fetched")
        assertIs<UpdateState.UpdateAvailable>(updater.state.value)
        assertEquals(emptyList(), dir.list()!!.toList())

        assertFalse(updater.installAndExit(File(dir, "Latch-1.5.0.dmg").path))
        assertEquals(emptyList(), launched)
    }

    @Test
    fun `a release without a macOS build is not offered`() = runBlocking<Unit> {
        assets = listOf("LatchSetup.msi", "latch-1.5.0-linux-x64.tar.gz", "latch_1.5.0_amd64.deb")

        updater.check(force = true)

        assertEquals(UpdateState.UpToDate, updater.state.value)
    }

    @Test
    fun `the current or an older release is not offered`() = runBlocking<Unit> {
        tag = "v1.4.3"
        updater.check(force = true)
        assertEquals(UpdateState.UpToDate, updater.state.value)
    }

    @Test
    fun `a release page outside this repository is refused`() = runBlocking<Unit> {
        htmlUrl = "https://example.com/latch/releases/tag/v1.5.0"
        updater.check(force = true)
        assertIs<UpdateState.Error>(updater.state.value)

        htmlUrl = null
        updater.check(force = true)
        assertIs<UpdateState.Error>(updater.state.value)
    }
}
