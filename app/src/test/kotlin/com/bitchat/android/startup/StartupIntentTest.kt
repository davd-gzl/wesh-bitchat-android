package com.bitchat.android.startup

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import com.bitchat.android.ui.NotificationManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 33])
class StartupIntentTest {
    @Test fun retainsOnlyBoundedQrAndPrivateNavigationWithoutQuitOrGeographicRoute() {
        val input = Intent().setData(Uri.parse("bitchat://verify?synthetic=public-proof"))
            .putExtra(NotificationManager.EXTRA_OPEN_PRIVATE_CHAT, true)
            .putExtra(NotificationManager.EXTRA_PEER_ID, "synthetic-peer")
            .putExtra(NotificationManager.EXTRA_SENDER_NICKNAME, "Synthetic contact")
            .putExtra(NotificationManager.EXTRA_OPEN_GEOHASH_CHAT, true)
            .putExtra(NotificationManager.EXTRA_GEOHASH, "synthetic")
            .putExtra("ACTION_QUIT_APP", true)
            .putExtra("unrelated_payload", "discard")
        val kept = boundedWeshStartupIntent(input)
        assertEquals(input.data, kept.data)
        assertEquals(setOf(NotificationManager.EXTRA_OPEN_PRIVATE_CHAT, NotificationManager.EXTRA_PEER_ID,
            NotificationManager.EXTRA_SENDER_NICKNAME), kept.extras!!.keySet())
        assertFalse(kept.getBooleanExtra("ACTION_QUIT_APP", false))
    }

    @Test fun keepsOnlyTheEraseShortcutAction() {
        val shortcut = boundedWeshStartupIntent(Intent(com.bitchat.android.ui.pave.PaveShortcuts.ACTION_HOLD_TO_ERASE).putExtra("unrelated_payload", "discard"))
        assertEquals(com.bitchat.android.ui.pave.PaveShortcuts.ACTION_HOLD_TO_ERASE, shortcut.action)
        assertNull(shortcut.extras)
        assertNull(boundedWeshStartupIntent(Intent("org.example.OTHER")).action)
    }

    @Test fun rejectsOversizedFieldsAndUnrelatedUri() {
        val oversized = Intent().setData(Uri.parse("bitchat://verify?x=" + "a".repeat(4096)))
            .putExtra(NotificationManager.EXTRA_OPEN_PRIVATE_CHAT, true)
            .putExtra(NotificationManager.EXTRA_PEER_ID, "a".repeat(129))
            .putExtra(NotificationManager.EXTRA_SENDER_NICKNAME, "é".repeat(128))
        val kept = boundedWeshStartupIntent(oversized)
        assertNull(kept.data)
        assertFalse(kept.hasExtra(NotificationManager.EXTRA_PEER_ID))
        assertFalse(kept.hasExtra(NotificationManager.EXTRA_SENDER_NICKNAME))
        assertNull(boundedWeshStartupIntent(Intent().setData(Uri.parse("https://example.invalid/"))).data)
    }

    @Test fun pendingRouteSurvivesActualParcelRoundTripAndIsConsumedOnce() {
        val route = boundedWeshStartupIntent(Intent().setData(Uri.parse("bitchat://verify?synthetic=latest-proof")))
        val state = Bundle().apply {
            putBoolean("wesh_pending_route", true)
            putParcelable("wesh_pending_intent", route)
        }
        val parcel = Parcel.obtain()
        val restored = try {
            state.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel).apply { classLoader = Intent::class.java.classLoader }
        } finally { parcel.recycle() }
        @Suppress("DEPRECATION")
        val restoredIntent = restored.getParcelable<Intent>("wesh_pending_intent")!!
        val gate = StartupRouteGate(restored.getBoolean("wesh_pending_route"))
        var count = 0
        gate.dispatchIfReady(false, true) { count++ }
        gate.dispatchIfReady(true, true) {
            count++
            assertEquals(route.data, boundedWeshStartupIntent(restoredIntent).data)
        }
        gate.dispatchIfReady(true, true) { count++ }
        assertEquals(1, count)
    }
}
