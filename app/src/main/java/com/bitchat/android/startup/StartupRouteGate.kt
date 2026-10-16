package com.bitchat.android.startup

/** Main-thread confined. The Activity stores only the latest Intent; this owns consumption. */
internal class StartupRouteGate(var pending: Boolean = true) {
    fun dispatchIfReady(runtimeReady: Boolean, onboardingComplete: Boolean, dispatch: () -> Unit) {
        if (!runtimeReady || !onboardingComplete || !pending) return
        pending = false
        dispatch()
    }
}
