package com.bitchat.android.ui

import android.app.Application
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationStorageCipher
import com.bitchat.android.services.SeenReceiptAdmission
import com.bitchat.android.startup.WeshRecoveryGate
import java.io.File
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual marker/store/ordered helper with owned files and RAM gates; no app graph or providers. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class PanicIntentOrderingTest {
    @get:Rule val directory = TemporaryFolder()

    @Before fun setUp() {
        AppStateStore.setConversationRepositoryForTest(null)
        AppStateStore.resetPrivateConversationAdmissionForTest()
        AppStateStore.clear()
    }

    @After fun tearDown() {
        AppStateStore.resetPrivateConversationAdmissionForTest()
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
    }

    @Test fun `failed marker commit cannot admit owner drain or destructive continuation`() = runTest {
        rejectFailedIntent(Fault.Throw)
    }

    @Test fun `missing marker after a returned commit cannot admit owner drain`() = runTest {
        rejectFailedIntent(Fault.Drop)
    }

    @Test fun `incorrect marker readback cannot admit owner drain`() = runTest {
        rejectFailedIntent(Fault.Corrupt)
    }

    @Test fun `unreadable marker entry cannot admit owner drain`() = runTest {
        rejectFailedIntent(Fault.Unreadable)
    }

    private suspend fun TestScope.rejectFailedIntent(fault: Fault) {
        val f = fixture(this)
        f.fault = fault
        f.fence()

        assertFalse(f.run())

        assertEquals("unconfirmed durable intent hands the erase to Android", 1, f.androidClears)
        assertEquals("no destructive stage before checked intent", 0, f.erases)
        assertEquals(0, f.cipher.destroys)
        f.assertRamClosed()
        assertArrayEquals(f.opaque, f.data.readBytes())
        if (fault == Fault.Unreadable) assertFalse(f.coldAllows())
    }

    @Test fun `a wipe failure occurs only after intent is durable and Android clears all app data`() = runTest {
        val f = fixture(this)
        f.fence()
        f.wipeFailure = IllegalStateException("Synthetic wipe failure")

        assertFalse(f.run())

        assertFalse("a cold process must find intent after a wipe failure", f.coldAllows())
        assertEquals(1, f.erases)
        assertEquals(1, f.androidClears)
        f.assertRamClosed()
    }

    @Test fun `marker-only operation preserves opaque ciphertext without cipher access`() = runTest {
        val f = fixture(this)
        f.fence()

        assertTrue(f.store.establishPanicIntent(f.store.epoch))

        assertArrayEquals(byteArrayOf(1), f.marker.readBytes())
        assertArrayEquals(f.opaque, f.data.readBytes())
        assertEquals(0, f.cipher.destroys)
        assertEquals(0, f.cipher.encrypts)
        assertEquals(0, f.cipher.decrypts)
        assertFalse(f.coldAllows())
        f.assertRamClosed()
    }

    @Test fun `memory-only drafts cannot confirm durable panic intent`() = runTest {
        val store = WeshDraftStore(ioDispatcher = StandardTestDispatcher(testScheduler))
        store.fenceForPanic()
        assertFalse(store.establishPanicIntent(store.epoch))
        assertFalse(store.save(store.epoch, "public", "Synthetic fenced draft"))
    }

    @Test fun `unfenced and obsolete draft epochs never write panic authority`() = runTest {
        val f = fixture(this)
        assertFalse(f.store.establishPanicIntent(f.store.epoch))
        f.store.fenceForPanic()
        val old = f.store.epoch
        f.store.fenceForPanic()
        assertFalse(f.store.establishPanicIntent(old))
        assertEquals(0, f.markerCalls)
        assertFalse(f.marker.exists())
        assertEquals(0, f.cipher.destroys)
    }

    @Test fun `a superseded generation cannot acknowledge an otherwise durable marker`() = runTest {
        val f = fixture(this)
        f.fence()
        f.afterMarker = { f.store.fenceForPanic() }
        assertFalse(f.store.establishPanicIntent(f.store.epoch))
        assertFalse(f.coldAllows())
        assertEquals(0, f.cipher.destroys)
        assertArrayEquals(f.opaque, f.data.readBytes())
    }

    @Test fun `the actual cold reader rejects every partial atomic marker without payload access`() {
        for (suffix in listOf("", ".bak", ".new")) {
            val root = directory.newFolder()
            File(root, "wesh-drafts-wipe-v1$suffix").writeBytes(byteArrayOf(1))
            val gate = WeshRecoveryGate()
            if (EncryptedWeshDraftBackend.hasPendingWipe(root)) gate.requireRecovery()
            assertFalse(gate.allowsTransport(prepared = true))
        }
    }

    @Test fun `confirmed intent precedes the wipe and the restart`() = runTest {
        val f = fixture(this)
        f.fence()

        assertTrue(f.run())

        assertEquals(1, f.erases)
        assertEquals(0, f.androidClears)
        assertFalse(f.data.exists())
        assertFalse("the marker survives the wipe for the relaunch", f.coldAllows())
        assertEquals(listOf("intent", "erase", "restart"), f.order)
    }

    @Test fun `initial private fence rejects a retained callback before any IO await`() = runTest {
        val peer = "synthetic-private-conversation"
        fun message(id: String) = BitchatMessage(id = id, sender = "Synthetic sender", content = "Synthetic private text",
            timestamp = Date(1), isPrivate = true, senderPeerID = peer)
        assertTrue("positive RAM admission control", AppStateStore.addPrivateMessage(peer, message("synthetic-existing")))
        val old = requireNotNull(AppStateStore.capturePrivateConversationGeneration())
        val release = CompletableDeferred<Unit>()
        val callback = async {
            release.await()
            AppStateStore.addPrivateMessage(peer, message("synthetic-retired-callback"))
        }
        try {
            AppStateStore.fencePrivateConversationAdmissionForPanic()
            release.complete(Unit)
            assertFalse("a callback queued before panic cannot mutate private state", withTimeout(1_000) { callback.await() })
            assertNull(AppStateStore.capturePrivateConversationGeneration())
            assertFalse(AppStateStore.isCurrentPrivateConversationGeneration(old))
            assertEquals(setOf("synthetic-existing"), AppStateStore.privateMessages.value.values.flatten().map { it.id }.toSet())
        } finally { release.complete(Unit); withTimeout(1_000) { callback.cancelAndJoin() } }
    }

    private fun fixture(scope: TestScope) = Fixture(directory.newFolder(), scope)

    private enum class Fault { Throw, Drop, Corrupt, Unreadable }

    private class RecordingCipher : ConversationStorageCipher {
        var destroys = 0
        var encrypts = 0
        var decrypts = 0
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray { encrypts++; error("Unexpected synthetic encrypt") }
        override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray { decrypts++; error("Unexpected synthetic decrypt") }
        override fun destroyKey() { destroys++ }
    }

    private class Fixture(val root: File, scope: TestScope) {
        val cipher = RecordingCipher()
        val marker = File(root, "wesh-drafts-wipe-v1")
        val data = File(root, "wesh-drafts-v1.bin")
        val opaque = "Synthetic opaque ciphertext sentinel".toByteArray()
        val order = mutableListOf<String>()
        var fault: Fault? = null
        var afterMarker: () -> Unit = {}
        var markerCalls = 0
        var erases = 0
        var androidClears = 0
        var wipeFailure: Exception? = null
        var preferencesBlocked = false
        val recovery = WeshRecoveryGate()
        val seen = SeenReceiptAdmission()
        val backend = EncryptedWeshDraftBackend(root, cipher, commitMarker = { commit ->
            order += "intent"
            markerCalls++
            when (fault) {
                Fault.Throw -> error("Synthetic marker commit failure")
                Fault.Drop -> Unit
                Fault.Corrupt -> { commit(); marker.writeBytes(byteArrayOf(2)) }
                Fault.Unreadable -> { commit(); check(marker.delete()); check(marker.mkdir()) }
                null -> commit()
            }
            afterMarker()
        })
        val store = WeshDraftStore(backend, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler))

        init { data.writeBytes(opaque) }

        fun fence() {
            recovery.requireRecovery()
            seen.fenceForPanic()
            store.fenceForPanic()
            preferencesBlocked = true
        }

        suspend fun run(): Boolean {
            var restarted = false
            PanicResetCoordinator.eraseAndRestart(
                establishIntent = { store.establishPanicIntent(store.epoch) },
                wipe = {
                    order += "erase"
                    erases++
                    wipeFailure?.let { throw it }
                    // Stands in for the app's own file wipe, which leaves the marker for the relaunch.
                    check(data.delete())
                },
                restart = { order += "restart"; restarted = true },
                clearAppData = { androidClears++ }
            )
            return restarted
        }

        fun coldAllows(): Boolean {
            val gate = WeshRecoveryGate()
            try {
                if (EncryptedWeshDraftBackend.hasPendingWipe(root)) gate.requireRecovery()
            } catch (_: Exception) { gate.requireRecovery() }
            return gate.allowsTransport(prepared = true)
        }

        fun assertRamClosed() {
            assertTrue(recovery.recoveryRequired.value)
            assertTrue(preferencesBlocked)
            assertNull(seen.capture())
            assertFalse(store.save(store.epoch, "public", "Synthetic refused edit"))
        }
    }
}
