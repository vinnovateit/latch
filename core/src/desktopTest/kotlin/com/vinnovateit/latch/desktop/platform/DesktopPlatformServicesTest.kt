package com.vinnovateit.latch.desktop.platform

import com.vinnovateit.latch.core.platform.UserNotifier
import com.vinnovateit.latch.desktop.platform.linux.LinuxCredentialStore
import com.vinnovateit.latch.desktop.platform.linux.LinuxSystemActions
import com.vinnovateit.latch.desktop.platform.linux.LinuxWifiPlatform
import com.vinnovateit.latch.desktop.platform.mac.MacKeychainCredentialStore
import com.vinnovateit.latch.desktop.platform.mac.MacSystemActions
import com.vinnovateit.latch.desktop.platform.mac.MacWifiPlatform
import com.vinnovateit.latch.desktop.platform.windows.DpapiCredentialStore
import com.vinnovateit.latch.desktop.platform.windows.WindowsSystemActions
import com.vinnovateit.latch.desktop.platform.windows.WindowsWifiPlatform
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `DesktopPlatformServices` used to fall through `else -> Linux...()` for any
 * OS it didn't recognize, which meant macOS silently ran Linux Wi-Fi/keyring/
 * system-action code instead of failing. Each OS must now resolve only its own
 * implementations, and anything unrecognised must fail. [DesktopOs] is an injectable seam
 * specifically so these can prove the dispatch without mutating `os.name`
 * across parallel tests.
 */
class DesktopPlatformServicesTest {
    private lateinit var directory: File
    private var previousDataDir: String? = null

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("latch-platform-os-").toFile()
        previousDataDir = System.getProperty("latch.dataDir")
        System.setProperty("latch.dataDir", directory.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        if (previousDataDir == null) System.clearProperty("latch.dataDir") else System.setProperty("latch.dataDir", previousDataDir)
        directory.deleteRecursively()
    }

    @Test
    fun `windows resolves windows implementations`() {
        val services = DesktopPlatformServices(notifier = NoOpNotifier, os = DesktopOs.WINDOWS)

        assertIs<DpapiCredentialStore>(services.credentials)
        assertIs<WindowsWifiPlatform>(services.wifi)
        assertIs<WindowsSystemActions>(services.systemActions)
    }

    @Test
    fun `linux resolves linux implementations`() {
        val services = DesktopPlatformServices(notifier = NoOpNotifier, os = DesktopOs.LINUX)

        assertIs<LinuxCredentialStore>(services.credentials)
        assertIs<LinuxWifiPlatform>(services.wifi)
        assertIs<LinuxSystemActions>(services.systemActions)
    }

    @Test
    fun `macOS resolves macOS implementations and never Linux implementations`() {
        val services = DesktopPlatformServices(notifier = NoOpNotifier, os = DesktopOs.MACOS)

        assertIs<MacKeychainCredentialStore>(services.credentials)
        assertIs<MacWifiPlatform>(services.wifi)
        assertIs<MacSystemActions>(services.systemActions)
        assertFalse(services.credentials is LinuxCredentialStore, "macOS must never use the Linux keyring/file store")
        assertFalse(services.wifi is LinuxWifiPlatform, "macOS must never use the Linux Wi-Fi platform")
        assertFalse(services.systemActions is LinuxSystemActions, "macOS must never write XDG autostart files")
        assertTrue(services.wifi.requiresKnownSsid, "macOS must refuse to treat an unreadable SSID as VIT Wi-Fi")
        assertFalse(File(directory, "credentials.bin").exists(), "macOS keeps credentials in the Keychain, not a file")
    }

    @Test
    fun `only macOS requires a known SSID before logging in`() {
        assertFalse(DesktopPlatformServices(notifier = NoOpNotifier, os = DesktopOs.WINDOWS).wifi.requiresKnownSsid)
        assertFalse(DesktopPlatformServices(notifier = NoOpNotifier, os = DesktopOs.LINUX).wifi.requiresKnownSsid)
    }

    @Test
    fun `an unsupported OS fails explicitly rather than defaulting to Linux`() {
        val error = assertFailsWith<IllegalStateException> {
            DesktopPlatformServices(notifier = NoOpNotifier, os = DesktopOs.UNSUPPORTED)
        }

        assertTrue(
            error.message.orEmpty().contains("Windows, Linux and macOS"),
            "the error should say what is actually supported, got: ${error.message}",
        )
    }

    @Test
    fun `an unsupported OS fails before any OS-specific side effect`() {
        assertFailsWith<IllegalStateException> {
            DesktopPlatformServices(notifier = NoOpNotifier, os = DesktopOs.UNSUPPORTED)
        }

        assertTrue(!directory.resolve("logs").exists(), "the logger must not have run before the OS check")
        assertTrue(!File(directory, "settings.json").exists(), "the settings store must not have run before the OS check")
    }
}

private object NoOpNotifier : UserNotifier {
    override fun notifyTransient(title: String, text: String, isError: Boolean) = Unit
}
