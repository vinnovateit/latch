package com.vinnovateit.latch.features.wifi.manager

/**
 * Display-ready status: the counterpart to the shared engine's typed
 * ConnectionStatus (core/wifi/ConnectionStatus.kt), which deliberately
 * doesn't resolve strings itself. EngineStatusBridge.toLegacyStatus() maps
 * one onto the other.
 *
 * The ConnectionStatusManager singleton that used to live in this file is
 * gone -- WiFiStatusViewModel/LatchWidgetUpdater read LatchAppGraph.engine.
 * status directly now instead of relaying through it.
 */
sealed class ConnectionStatus {
  object Idle : ConnectionStatus()
  object Success : ConnectionStatus()
  data class Failed(val message: String) : ConnectionStatus()
  companion object {
    data class Connecting(val message: String) : ConnectionStatus()
  }
}