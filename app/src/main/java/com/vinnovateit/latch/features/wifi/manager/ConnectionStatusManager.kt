package com.vinnovateit.latch.features.wifi.manager

import android.app.Application
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.vinnovateit.latch.features.wifi.widget.LatchWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

sealed class ConnectionStatus {
  object Idle : ConnectionStatus()
  object Success : ConnectionStatus()
  data class Failed(val message: String) : ConnectionStatus()
  companion object {
    data class Connecting(val message: String) : ConnectionStatus()
  }
}

object ConnectionStatusManager {
  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
  val status = _status.asStateFlow()
  private var applicationContext: Application? = null
  private val isInitialized = AtomicBoolean(false)

  fun initialize(context: Application) {
    if (isInitialized.getAndSet(true)) return
    applicationContext = context
  }

  fun postStatus(newStatus: ConnectionStatus) {
    _status.value = newStatus
    triggerWidgetUpdate() // Trigger an update every time the status changes

    if (newStatus is ConnectionStatus.Success || newStatus is ConnectionStatus.Failed) {
      scope.launch {
        delay(2000)
        if (_status.value == newStatus) {
          _status.value = ConnectionStatus.Idle
        }
      }
    }
  }

  private fun triggerWidgetUpdate() {
    applicationContext?.let {
      val request = OneTimeWorkRequestBuilder<LatchWidgetUpdater>().build()
      WorkManager.getInstance(it).enqueue(request)
    }
  }
}