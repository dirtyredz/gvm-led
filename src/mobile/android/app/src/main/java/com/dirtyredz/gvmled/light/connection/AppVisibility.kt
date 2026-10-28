package com.dirtyredz.gvmled.light.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one lifecycle seam for light work. The activity reports foreground and background here and
 * nowhere else; repeated or overlapping lifecycle calls collapse to one state, so a resume can never
 * start a second set of connections or synchronizers. Background releases every connection (and so
 * cancels every retry); foreground reconnects, and each new connection replays the saved intent.
 */
class AppVisibility {
    private val state = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = state.asStateFlow()

    fun enterForeground() {
        state.value = true
    }

    fun enterBackground() {
        state.value = false
    }
}
