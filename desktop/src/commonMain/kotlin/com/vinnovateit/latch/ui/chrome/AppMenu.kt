package com.vinnovateit.latch.ui.chrome

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The application actions the window chrome is allowed to offer, as opaque
 * lambdas.
 *
 * Deliberately says nothing about routes or destinations. The chrome renders a
 * menu; the application decides what the entries do. Inverting that would make
 * the window layer depend on navigation internals.
 */
@Immutable
data class AppMenuActions(
    /** Whether to offer Settings. Hidden when the nav rail already provides it. */
    val showSettings: Boolean,
    val onOpenSettings: () -> Unit,
    val onHowItWorks: () -> Unit,
    val onOpenAbout: () -> Unit,
)

/**
 * The bridge between the window chrome and whatever application state is mounted
 * beneath it.
 *
 * The title bar is a sibling *above* the app content, not an ancestor, so a
 * CompositionLocal cannot carry anything from the app up into it. Rather than
 * hoist navigation state out of LatchRoot into the window layer, or duplicate it
 * in both, the application publishes a small set of lambdas here and the chrome
 * reads them.
 *
 * [actions] being null means "no menu" and is the resting state: the chrome shows
 * no hamburger at all during first-run onboarding, credential setup, About and
 * the update screen, because nothing has published anything.
 *
 * One instance is created at the composition root and handed to both sides. It is
 * not global and holds no navigation state of its own.
 */
@Stable
class AppMenuHost {
    var actions: AppMenuActions? by mutableStateOf(null)
        private set

    /** Called by the application layer when its menu-capable shell mounts or changes. */
    fun publish(actions: AppMenuActions?) {
        this.actions = actions
    }
}
