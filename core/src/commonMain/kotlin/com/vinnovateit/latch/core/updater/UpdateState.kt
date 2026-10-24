package com.vinnovateit.latch.core.updater

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState

    /**
     * Checked, and this build is current. Distinct from [Idle] ("never checked"),
     * which is what lets Settings show "You are up to date" instead of appearing
     * to have done nothing.
     */
    data object UpToDate : UpdateState
    data class UpdateAvailable(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String,
        /**
         * The update cannot be installed from inside the app; [downloadUrl] is
         * then the release page, for the user to download and install by hand.
         */
        val manualInstall: Boolean = false,
    ) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Downloaded(val version: String, val filePath: String) : UpdateState

    /**
     * The user found an update and chose to postpone it. Distinct from [Idle]
     * for the same reason [UpToDate] is: reverting to "Not checked yet" after a
     * check that succeeded misreports what happened.
     */
    data class Dismissed(val version: String) : UpdateState
    data class Error(val message: String) : UpdateState
}
