package com.vinnovateit.latch.ui.onboarding

import com.vinnovateit.latch.core.data.PortalSessionEntity
import com.vinnovateit.latch.core.data.Session
import com.vinnovateit.latch.core.data.StatsDao
import com.vinnovateit.latch.core.platform.BuildInfo
import com.vinnovateit.latch.core.platform.ByteCounterSource
import com.vinnovateit.latch.core.platform.ByteCounts
import com.vinnovateit.latch.core.platform.CredentialStore
import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.KeyValueStore
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.NoOpLogger
import com.vinnovateit.latch.core.platform.PlatformCapabilities
import com.vinnovateit.latch.core.platform.PlatformServices
import com.vinnovateit.latch.core.platform.SystemActions
import com.vinnovateit.latch.core.platform.UserNotifier
import com.vinnovateit.latch.core.platform.WifiEvent
import com.vinnovateit.latch.core.platform.WifiPlatform
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Test doubles for the collaborators [com.vinnovateit.latch.ui.LatchRoot] needs.
 *
 * Deliberately inert: onboarding must not touch the network, the portal, the
 * Wi-Fi stack or the real credential store. Anything that would reach outside
 * the test either returns a fixed value or fails loudly.
 */

/** Records what onboarding stored, and reports presence the way the real store does. */
class FakeCredentialStore(private var stored: Pair<String, String>? = null) : CredentialStore {
    val saves = mutableListOf<Pair<String, String>>()

    override fun save(userId: String, password: String): Result<Unit> {
        saves += userId to password
        stored = userId to password
        return Result.success(Unit)
    }

    override fun userId(): String? = stored?.first
    override fun password(): String? = stored?.second
    override fun exists(): Boolean = stored != null
    override fun clear() {
        stored = null
    }
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, Any>()

    override fun getString(key: String, default: String): String = values[key] as? String ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        values[key] as? Set<String> ?: default

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun putStringSet(key: String, value: Set<String>) {
        values[key] = value
    }
}

/** Reports a machine with no Wi-Fi, so nothing tries to latch during the test. */
object OfflineWifiPlatform : WifiPlatform {
    override fun isWifiEnabled(): Boolean = false
    override fun isConnectedToWifi(): Boolean = false
    override fun currentSsid(): String? = null
    override fun gatewayIp(): String? = null
    override fun activeHandle(): NetworkHandle? = null
    override val events: Flow<WifiEvent> = emptyFlow()
}

object ZeroByteCounters : ByteCounterSource {
    override fun sample(): ByteCounts = ByteCounts(rxBytes = 0, txBytes = 0)
}

/** Onboarding has no business opening a connection; say so rather than hanging. */
object ForbiddenHttpTransport : HttpTransport {
    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection =
        error("onboarding must not make network calls, but something opened $url")
}

object SilentNotifier : UserNotifier {
    override fun notifyTransient(title: String, text: String, isError: Boolean) = Unit
}

object InertSystemActions : SystemActions {
    override fun openWifiSettings() = Unit
    override fun openUrl(url: String) = Unit
    override fun setAutostart(enabled: Boolean) = Unit
    override fun isAutostartEnabled(): Boolean = false
}

object TestBuildInfo : BuildInfo {
    override val versionName: String = "0.0.0-test"
    override val isDebug: Boolean = true
    override val isInstalled: Boolean = false
}

object TestCapabilities : PlatformCapabilities {
    override val supportsAutostart: Boolean = false
}

class FakePlatformServices(
    override val credentials: CredentialStore,
    override val settingsStore: KeyValueStore,
) : PlatformServices {
    override val logger: Logger = NoOpLogger
    override val buildInfo: BuildInfo = TestBuildInfo
    override val capabilities: PlatformCapabilities = TestCapabilities
    override val wifi: WifiPlatform = OfflineWifiPlatform
    override val counters: ByteCounterSource = ZeroByteCounters
    override val notifier: UserNotifier = SilentNotifier
    override val systemActions: SystemActions = InertSystemActions
    override val httpTransport: HttpTransport = ForbiddenHttpTransport
}

/** Empty, in-memory stand-in for the Room DAO. */
class FakeStatsDao : StatsDao {
    private val sessions = MutableStateFlow<List<Session>>(emptyList())
    private val portalSessions = MutableStateFlow<List<PortalSessionEntity>>(emptyList())

    override suspend fun insertSession(session: Session): Long {
        sessions.value = sessions.value + session
        return sessions.value.size.toLong()
    }

    override fun getAllSessions(): Flow<List<Session>> = sessions

    override suspend fun clearAllSessions() {
        sessions.value = emptyList()
    }

    override suspend fun insertAllPortalSessions(sessions: List<PortalSessionEntity>) {
        portalSessions.value = portalSessions.value + sessions
    }

    override fun getAllPortalSessions(): Flow<List<PortalSessionEntity>> = portalSessions

    override suspend fun clearAllPortalSessions() {
        portalSessions.value = emptyList()
    }
}
