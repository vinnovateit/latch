package com.vinnovateit.latch.ui.stats

import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.model.AggregatedDayRecord
import com.vinnovateit.latch.core.model.HistoryChartItem
import com.vinnovateit.latch.core.model.LiveConnectionStatus
import com.vinnovateit.latch.core.model.PortalSessionRecord
import com.vinnovateit.latch.core.model.StatsOverviewMetrics
import com.vinnovateit.latch.core.mp.JFile as File
import com.vinnovateit.latch.core.mp.ioDispatcher
import com.vinnovateit.latch.core.mp.nowMillis
import com.vinnovateit.latch.core.mp.userHome
import com.vinnovateit.latch.core.mp.useOutput
import com.vinnovateit.latch.core.platform.PlatformServices
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.StatsInsights
import com.vinnovateit.latch.core.stats.computeStatsInsights
import com.vinnovateit.latch.core.stats.formatDate
import com.vinnovateit.latch.core.stats.generatePortalHtmlReport
import com.vinnovateit.latch.core.stats.nonZeroSessions
import com.vinnovateit.latch.core.stats.sessionsForDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which pane of Stats is on screen. */
enum class StatsSubScreen { Main, SessionHistory }

/**
 * The test surface. The view renders [uiState] and sends [onEvent]; it holds no
 * domain state and makes no domain calls of its own.
 */
interface StatsPresentation {
    val uiState: StateFlow<StatsUiState>
    fun onEvent(event: StatsEvent)
}

/** Everything the Stats screen renders, derived in one place. */
data class StatsUiState(
    val liveStatus: LiveConnectionStatus? = null,
    val isSyncing: Boolean = false,
    val isHistoryLoaded: Boolean = false,
    val speedUnit: String = "bps",
    val chartPalette: String = "Material Dynamic",
    val usePureBlack: Boolean = false,
    val allDayRecords: List<AggregatedDayRecord> = emptyList(),
    val metrics: StatsOverviewMetrics = StatsOverviewMetrics(0L, 0L, 0L, 0),
    val insights: StatsInsights = computeStatsInsights(emptyList()),
    val chartItems: List<HistoryChartItem> = emptyList(),
    val selectedDayTimestamp: Long? = null,
    val todayKey: String = "",
    val selectedDateKey: String = "",
    val isToday: Boolean = true,
    val displayedSessions: List<PortalSessionRecord> = emptyList(),
    val currentSubScreen: StatsSubScreen = StatsSubScreen.Main,
    val menuExpanded: Boolean = false,
)

sealed interface StatsEvent {
    /** Initial history sync when the screen opens. */
    data object RefreshHistory : StatsEvent
    /** Forced resync from the overflow menu. */
    data object ResyncHistory : StatsEvent
    data object ClearHistory : StatsEvent
    data object ExportReport : StatsEvent
    data class SelectDay(val timestamp: Long?) : StatsEvent
    data object OpenSessionHistory : StatsEvent
    data object CloseSessionHistory : StatsEvent
    data object Back : StatsEvent
    data class SetMenuExpanded(val expanded: Boolean) : StatsEvent
}

/** Narrow adapter over the singleton so the implementation can be given its settings. */
interface StatsSettings {
    val speedUnits: StateFlow<String>
    val chartPalette: StateFlow<String>
    val usePureBlack: StateFlow<Boolean>
}

object DesktopStatsSettings : StatsSettings {
    override val speedUnits: StateFlow<String> get() = SettingsManager.speedUnits
    override val chartPalette: StateFlow<String> get() = SettingsManager.chartPalette
    override val usePureBlack: StateFlow<Boolean> get() = SettingsManager.usePureBlack
}

/**
 * Host callbacks the presentation dispatches on [StatsEvent.Back] and
 * [StatsEvent.ClearHistory]. A mutable adapter rather than constructor lambdas
 * so a remembered presentation never goes stale when the host recomposes.
 */
class StatsNavigation {
    var onBack: (() -> Unit)? = null
    var onClearHistory: (() -> Unit)? = null
}

/**
 * Deep presentation module for desktop Stats: owns the scope, collects the
 * domain flows, derives everything the view renders, and performs the sync and
 * export side effects. Dependencies are supplied, never constructed here.
 */
class StatsPresentationImpl(
    private val sessions: SessionRepository,
    private val platform: PlatformServices,
    private val settings: StatsSettings,
    private val navigation: StatsNavigation = StatsNavigation(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : StatsPresentation {

    private val _uiState = MutableStateFlow(StatsUiState())
    override val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    /** Held fixed for the screen's lifetime, as the old `remember { ... }` did. */
    private val todayKey = formatDate(nowMillis(), "yyyy-MM-dd")
    private val selectedDay = MutableStateFlow<Long?>(null)

    private val subScreen = MutableStateFlow(StatsSubScreen.Main)
    private val menuExpanded = MutableStateFlow(false)
    private var lastLogKey: Triple<Int, Int, String>? = null

    init {
        updateState()
        // One serialized context: concurrent collectors could otherwise publish
        // a state derived from an older flow snapshot after a newer one.
        val updateContext = scope.coroutineContext + Dispatchers.Default.limitedParallelism(1)
        listOf<StateFlow<*>>(
            sessions.liveStatus,
            sessions.portalHistory,
            sessions.isSyncing,
            sessions.isHistoryLoaded,
            sessions.aggregatedDayRecords,
            sessions.overviewMetrics,
            sessions.statsInsights,
            sessions.chartItems,
            settings.speedUnits,
            settings.chartPalette,
            settings.usePureBlack,
            selectedDay,
            subScreen,
            menuExpanded,
        ).forEach { flow ->
            scope.launch(updateContext) { flow.collect { updateState() } }
        }
    }

    override fun onEvent(event: StatsEvent) {
        when (event) {
            StatsEvent.RefreshHistory -> sync(force = false)
            StatsEvent.ResyncHistory -> sync(force = true)
            StatsEvent.ClearHistory -> navigation.onClearHistory?.invoke()
            StatsEvent.ExportReport -> exportReport()
            is StatsEvent.SelectDay -> selectedDay.value = event.timestamp
            StatsEvent.OpenSessionHistory -> subScreen.value = StatsSubScreen.SessionHistory
            StatsEvent.CloseSessionHistory -> subScreen.value = StatsSubScreen.Main
            StatsEvent.Back -> navigation.onBack?.invoke()
            is StatsEvent.SetMenuExpanded -> menuExpanded.value = event.expanded
        }
    }

    /** Cancel the owned scope when the view leaves composition. */
    fun close() {
        scope.cancel()
    }

    private fun updateState() {
        // Read the selection once; two reads can straddle a SelectDay event and
        // publish a timestamp that disagrees with the sessions it filtered.
        val day = selectedDay.value
        val history = sessions.portalHistory.value
        val selectedDateKey = day?.let { formatDate(it, "yyyy-MM-dd") } ?: todayKey
        val displayed = sessionsForDay(history, selectedDateKey)
        val syncing = sessions.isSyncing.value

        _uiState.value = StatsUiState(
            liveStatus = sessions.liveStatus.value,
            isSyncing = syncing,
            isHistoryLoaded = sessions.isHistoryLoaded.value,
            speedUnit = settings.speedUnits.value,
            chartPalette = settings.chartPalette.value,
            usePureBlack = settings.usePureBlack.value,
            allDayRecords = sessions.aggregatedDayRecords.value,
            metrics = sessions.overviewMetrics.value,
            insights = sessions.statsInsights.value,
            chartItems = sessions.chartItems.value,
            selectedDayTimestamp = day,
            todayKey = todayKey,
            selectedDateKey = selectedDateKey,
            isToday = selectedDateKey == todayKey,
            displayedSessions = displayed,
            currentSubScreen = subScreen.value,
            menuExpanded = menuExpanded.value,
        )

        // Preserve the old LaunchedEffect's trigger keys: log only when one of
        // these three actually changes, not on every flow emission.
        val logKey = Triple(history.size, displayed.size, selectedDateKey)
        if (logKey != lastLogKey) {
            lastLogKey = logKey
            platform.logger.d(
                "StatsScreen",
                "UI state: portalHistory=${history.size} displayed=${displayed.size} " +
                    "days=${sessions.aggregatedDayRecords.value.size} charts=${sessions.chartItems.value.size} " +
                    "date=$selectedDateKey isSyncing=$syncing",
            )
        }
    }

    private fun sync(force: Boolean) {
        val userId = platform.credentials.userId()
        val password = platform.credentials.password()
        if (force) {
            platform.logger.d(
                "StatsScreen",
                "Resync History clicked: credentialsPresent=${!userId.isNullOrBlank() && !password.isNullOrBlank()} userIdLen=${userId?.length ?: 0}",
            )
        }
        if (!userId.isNullOrBlank() && !password.isNullOrBlank()) {
            scope.launch {
                val result = withContext(ioDispatcher) {
                    sessions.syncPortalHistory(userId, password, force = force)
                }
                if (force) {
                    platform.logger.d(
                        "StatsScreen",
                        "Resync finished: ok=${result.isSuccess} error=${result.exceptionOrNull()?.message}",
                    )
                }
            }
        } else if (force) {
            platform.logger.w("StatsScreen", "Cannot resync portal history: missing credentials")
        }
    }

    private fun exportReport() {
        scope.launch {
            try {
                val userHome = userHome()
                val downloadsDir = File(userHome, "Downloads").takeIf { it.exists() && it.isDirectory }
                    ?: File(userHome)
                val reportFile = File(downloadsDir, "latch-session-report-${nowMillis()}.html")
                reportFile.outputStream().useOutput { stream ->
                    generatePortalHtmlReport(
                        sessions = nonZeroSessions(sessions.portalHistory.value),
                        outputStream = stream,
                        appVersion = "Desktop",
                        userId = platform.credentials.userId() ?: "",
                    )
                }
                platform.systemActions.openUrl("file://${reportFile.path}")
            } catch (e: Exception) {
                platform.logger.e("StatsScreen", "Failed to export HTML report", e)
            }
        }
    }
}
