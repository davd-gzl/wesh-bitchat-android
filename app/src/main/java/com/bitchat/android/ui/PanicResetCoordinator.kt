package com.bitchat.android.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The erase never resumes in-process: it ends in a restart or in Android clearing all app data. */
internal object PanicResetCoordinator {
    /** Best effort and synchronous at the tap; the process kill and data clear follow anyway. */
    fun stopTransports(vararg stops: () -> Unit) {
        stops.forEach { stop -> try { stop() } catch (_: Exception) { } }
    }

    fun launchPanic(scope: CoroutineScope, action: suspend () -> Unit): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) { action() }

    /** A refused, failed or cancelled in-app erase still erases: Android then clears all app data. */
    suspend fun eraseOrClearAppData(erase: suspend () -> Boolean, clearAppData: () -> Unit) {
        val erased = try {
            erase()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            clearAppData()
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!erased) clearAppData()
    }

    /** Durable intent, the app's own wipe, then a restart; anything short of that hands the erase to Android. */
    suspend fun eraseAndRestart(
        establishIntent: suspend () -> Boolean,
        wipe: suspend () -> Unit,
        restart: () -> Unit,
        clearAppData: () -> Unit
    ) = eraseOrClearAppData(
        erase = {
            if (!establishIntent()) false else {
                wipe()
                restart()
                true
            }
        },
        clearAppData = clearAppData
    )
}
