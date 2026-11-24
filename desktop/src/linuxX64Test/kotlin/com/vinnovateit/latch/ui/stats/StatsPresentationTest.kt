package com.vinnovateit.latch.ui.stats

import com.vinnovateit.latch.core.data.PortalSessionEntity
import com.vinnovateit.latch.core.data.Session
import com.vinnovateit.latch.core.data.StatsDao
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.mp.JHttpConnection
import com.vinnovateit.latch.core.mp.JUrl
import com.vinnovateit.latch.core.mp.nowMillis
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
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import com.vinnovateit.latch.core.stats.dayKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the presentation through its interface only: the view surface is
 * [StatsPresentation.uiState] plus [StatsPresentation.onEvent].
 */
class StatsPresentationTest {

    private class FakeSettings : StatsSettings {
        override val speedUnits = MutableStateFlow("bps")
        override val chartPalette = MutableStateFlow("Material Dynamic")
        override val usePureBlack = MutableStateFlow(false)
    }

    private class FakeDao(
        private val portal: MutableStateFlow<List<PortalSessionEntity>>,
    ) : StatsDao {
        private val local = MutableStateFlow<List<Session>>(emptyList())
        override suspend fun insertSession(session: Session): Long = 1L
        override fun getAllSessions(): Flow<List<Session>> = local
        override suspend fun clearAllSessions() = Unit
        override suspend fun insertAllPortalSessions(sessions: List<PortalSessionEntity>) {
            portal.value = portal.value + sessions
        }
        override fun getAllPortalSessions(): Flow<List<PortalSessionEntity>> = portal
        override suspend fun clearAllPortalSessions() {
            portal.value = emptyList()
        }
    }

    private object FakePlatform : PlatformServices {
        override val logger: Logger = NoOpLogger
        override val buildInfo = object : BuildInfo {
            override val versionName = "test"
            override val isDebug = true
            override val isInstalled = false
        }
        override val capabilities = object : PlatformCapabilities {
            override val supportsAutostart = false
        }
        override val settingsStore = object : KeyValueStore {
            override fun getString(key: String, default: String) = default
            override fun getBoolean(key: String, default: Boolean) = default
            override fun getStringSet(key: String, default: Set<String>) = default
            override fun putString(key: String, value: String) = Unit
            override fun putBoolean(key: String, value: Boolean) = Unit
            override fun putStringSet(key: String, value: Set<String>) = Unit
        }
        override val credentials = object : CredentialStore {
            override fun save(userId: String, password: String) = Result.success(Unit)
            override fun userId(): String? = null
            override fun password(): String? = null
            override fun exists() = false
            override fun clear() = Result.success(Unit)
        }
        override val wifi = object : WifiPlatform {
            override fun isWifiEnabled() = false
            override fun isConnectedToWifi() = false
            override fun currentSsid(): String? = null
            override fun gatewayIp(): String? = null
            override fun activeHandle(): NetworkHandle? = null
            override val events: Flow<WifiEvent> = emptyFlow()
        }
        override val counters = object : ByteCounterSource {
            override fun sample(): ByteCounts = ByteCounts(0L, 0L)
        }
        override val notifier = object : UserNotifier {
            override fun notifyTransient(title: String, text: String, isError: Boolean) = Unit
        }
        override val systemActions = object : SystemActions {
            override fun openWifiSettings() = Unit
            override fun openUrl(url: String) = Unit
            override fun setAutostart(enabled: Boolean) = Unit
            override fun isAutostartEnabled() = false
        }
        override val httpTransport = object : HttpTransport {
            override fun open(url: JUrl, handle: NetworkHandle?): JHttpConnection =
                error("the presentation test must not open connections")
        }
    }

    private fun entity(login: Long, up: Long, down: Long) = PortalSessionEntity(
        location = "",
        macAddress = "",
        loginTime = login,
        logoutTime = login,
        durationFormatted = "",
        durationMillis = 0L,
        uploadBytes = up,
        downloadBytes = down,
        totalBytes = up + down,
    )

    @Test
    fun `presentation derives the selected-day rule and routes events`() = runBlocking {
        val now = nowMillis()
        val yesterday = now - 86_400_000L
        val dao = FakeDao(
            MutableStateFlow(
                listOf(
                    entity(now, 10L, 20L),
                    entity(yesterday, 30L, 40L),
                    entity(now, 0L, 0L),
                )
            )
        )

        val sessions = SessionRepository(
            statsDao = dao,
            throughput = ThroughputMonitor(object : ByteCounterSource {
                override fun sample(): ByteCounts = ByteCounts(0L, 0L)
            }),
            activeHandle = { null },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            logger = NoOpLogger,
        )
        sessions.initialize()

        val nav = StatsNavigation()
        val presentation = StatsPresentationImpl(
            sessions = sessions,
            platform = FakePlatform,
            settings = FakeSettings(),
            navigation = nav,
        )

        try {
            val initial = presentation.uiState.first { it.displayedSessions.isNotEmpty() }
            assertEquals(1, initial.displayedSessions.size)
            assertEquals(dayKey(now), initial.selectedDateKey)
            assertEquals(true, initial.isToday)

            presentation.onEvent(StatsEvent.SelectDay(yesterday))
            val selected = presentation.uiState.first {
                it.selectedDayTimestamp == yesterday && it.displayedSessions.isNotEmpty()
            }
            assertEquals(1, selected.displayedSessions.size)
            assertEquals(30L, selected.displayedSessions[0].uploadBytes)
            assertEquals(false, selected.isToday)

            presentation.onEvent(StatsEvent.OpenSessionHistory)
            assertEquals(
                StatsSubScreen.SessionHistory,
                presentation.uiState.first { it.currentSubScreen == StatsSubScreen.SessionHistory }.currentSubScreen,
            )
        } finally {
            presentation.close()
            sessions.close()
        }
    }
}
