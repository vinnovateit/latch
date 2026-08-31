package com.vinnovateit.latch.core.updater

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class UpdateAvailable(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String,
    ) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Downloaded(val version: String, val filePath: String) : UpdateState
    data class Dismissed(val version: String) : UpdateState
    data class Error(val message: String) : UpdateState
}
