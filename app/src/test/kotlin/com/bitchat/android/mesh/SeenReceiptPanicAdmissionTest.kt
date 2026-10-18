package com.bitchat.android.mesh

import android.app.Application
import android.content.SharedPreferences
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.services.SeenReceiptAdmission
import com.bitchat.android.startup.WeshRecoveryGate
import com.bitchat.android.ui.clearSeenForPanic
import com.bitchat.android.ui.resumeAfterWeshPanicWipe
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual retry completions and Seen persistence, with no mesh, Context or Keystore construction. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class SeenReceiptPanicAdmissionTest {
    @Test fun `old BLE receipt completion during panic cannot recreate Seen storage`() = runTest {
        val fixture = Fixture()
        val pending = holdFinalAttempt(fixture, OLD_ID)
        val recovery = WeshRecoveryGate().apply { requireRecovery() }
        try {
            assertTrue(fixture.clearForPanic())
        } finally {
            pending.complete(Unit)
        }
        advanceUntilIdle()

        assertTrue(recovery.recoveryRequired.value)
        assertFalse("the old completed window must not recreate its receipt marker", fixture.seen.hasReadReceiptBeenSent(OLD_ID))
        assertEquals(mapOf(NEIGHBOR to NEIGHBOR_VALUE), fixture.storage.memory)
        assertEquals(mapOf(NEIGHBOR to NEIGHBOR_VALUE), fixture.storage.disk)
    }

    @Test fun `old MeshCore receipt completion after verified resume cannot replace fresh Seen state`() = runTest {
        val fixture = Fixture()
        var acceptedHooks = 0
        val pending = holdFinalAttempt(fixture, OLD_ID) { acceptedHooks++ }
        val recovery = WeshRecoveryGate().apply { requireRecovery() }
        try {
            val cleared = fixture.clearForPanic()
            assertTrue(resume(cleared, recovery, fixture))
            completeFresh(fixture, FRESH_ID)
        } finally {
            pending.complete(Unit)
        }
        advanceUntilIdle()

        assertFalse(recovery.recoveryRequired.value)
        assertFalse("an old completion stays stale after admission reopens", fixture.seen.hasReadReceiptBeenSent(OLD_ID))
        assertTrue(fixture.seen.hasReadReceiptBeenSent(FRESH_ID))
        assertEquals(expectedMap(FRESH_ID), fixture.storage.memory)
        assertEquals(expectedMap(FRESH_ID), fixture.storage.disk)
        // The transport hook's accepted-window behavior is independently preserved.
        assertEquals(1, acceptedHooks)
    }

    @Test fun `fresh BLE completion persists the accepted receipt`() = runTest {
        val fixture = Fixture()
        completeFresh(fixture, FRESH_ID)
        advanceUntilIdle()
        assertTrue(fixture.seen.hasReadReceiptBeenSent(FRESH_ID))
        assertEquals(expectedMap(FRESH_ID), fixture.storage.memory)
        assertEquals(expectedMap(FRESH_ID), fixture.storage.disk)
    }

    @Test fun `fresh MeshCore completion records Seen before its accepted hook`() = runTest {
        val fixture = Fixture()
        var hooks = 0
        var seenAtHook = false
        completeFresh(fixture, FRESH_ID) {
            hooks++
            seenAtHook = fixture.seen.hasReadReceiptBeenSent(FRESH_ID)
        }
        advanceUntilIdle()
        assertEquals(1, hooks)
        assertTrue(seenAtHook)
        assertEquals(expectedMap(FRESH_ID), fixture.storage.disk)
    }

    @Test fun `rejected retry window neither opens Seen nor runs the accepted hook`() = runTest {
        var providerCalls = 0
        var hooks = 0
        sender().enqueue("synthetic-rejected", sendAttempt = { false }, onComplete = readReceiptCompletion(
            OLD_ID, expectedToken = SeenReceiptAdmission().capture(), seenStore = { providerCalls++; error("Rejected windows do not open storage") },
            onAccepted = { hooks++ }
        ))
        advanceUntilIdle()
        assertEquals(0, providerCalls)
        assertEquals(0, hooks)
    }

    @Test fun `accepted hook retains its behavior if Seen marking throws`() = runTest {
        var providerCalls = 0
        var hooks = 0
        sender().enqueue("synthetic-storage-error", sendAttempt = { true }, onComplete = readReceiptCompletion(
            OLD_ID, expectedToken = SeenReceiptAdmission().capture(), seenStore = { providerCalls++; error("Synthetic unavailable Seen storage") },
            onAccepted = { hooks++ }
        ))
        advanceUntilIdle()
        assertEquals(1, providerCalls)
        assertEquals(1, hooks)
    }

    @Test fun `failed Seen disk mutation cannot release the global recovery gate`() = runTest {
        val fixture = Fixture(seeded = true)
        val oldDisk = fixture.storage.disk.toMap()
        fixture.storage.commitSucceeds = false
        val recovery = WeshRecoveryGate().apply { requireRecovery() }
        var preferenceAttempts = 0
        val resumed = resume(fixture.clearForPanic(), recovery, fixture) { preferenceAttempts++ }

        assertFalse("RAM-only Seen deletion is not confirmed cleanup", resumed)
        assertTrue(recovery.recoveryRequired.value)
        assertEquals(1, preferenceAttempts)
        assertEquals(oldDisk, fixture.storage.disk)
    }

    @Test fun `Seen persistence exception keeps recovery closed while other cleanup is attempted`() = runTest {
        val fixture = Fixture(seeded = true)
        fixture.storage.writeFailure = IllegalStateException("Synthetic Seen write failure")
        val recovery = WeshRecoveryGate().apply { requireRecovery() }
        var preferenceAttempts = 0
        val resumed = resume(fixture.clearForPanic(), recovery, fixture) { preferenceAttempts++ }

        assertFalse(resumed)
        assertTrue(recovery.recoveryRequired.value)
        assertEquals(1, preferenceAttempts)
    }

    @Test fun `successful commit with a retained Seen key still keeps recovery closed`() = runTest {
        val fixture = Fixture(seeded = true)
        fixture.storage.retainRemovedValues = true
        val recovery = WeshRecoveryGate().apply { requireRecovery() }
        var preferenceAttempts = 0
        val resumed = resume(fixture.clearForPanic(), recovery, fixture) { preferenceAttempts++ }

        assertFalse("absence must be checked after the Seen commit", resumed)
        assertTrue(recovery.recoveryRequired.value)
        assertEquals(1, preferenceAttempts)
        assertTrue(fixture.storage.memory.containsKey(SEEN_KEY))
    }

    @Test fun `failed Seen cleanup can be retried before fresh receipt persistence resumes`() = runTest {
        val fixture = Fixture(seeded = true)
        val recovery = WeshRecoveryGate().apply { requireRecovery() }
        fixture.storage.commitSucceeds = false
        val firstResult = resume(fixture.clearForPanic(), recovery, fixture)
        val blockedAfterFailure = recovery.recoveryRequired.value
        fixture.storage.commitSucceeds = true
        val retryResult = resume(fixture.clearForPanic(), recovery, fixture)
        completeFresh(fixture, FRESH_ID)
        advanceUntilIdle()

        assertFalse(firstResult)
        assertTrue(blockedAfterFailure)
        assertTrue(retryResult)
        assertFalse(recovery.recoveryRequired.value)
        assertFalse(fixture.seen.hasReadReceiptBeenSent(OLD_ID))
        assertTrue(fixture.seen.hasReadReceiptBeenSent(FRESH_ID))
        assertEquals(expectedMap(FRESH_ID), fixture.storage.disk)
    }

    @Test fun `explicit unconfirmed Seen result cannot be compensated by every other successful store`() = runTest {
        val recovery = WeshRecoveryGate().apply { requireRecovery() }
        var preferenceAttempts = 0
        val resumed = resume(false, recovery) { preferenceAttempts++ }
        assertFalse(resumed)
        assertTrue(recovery.recoveryRequired.value)
        assertEquals(1, preferenceAttempts)
    }

    @Test fun `fenced receipt admission refuses a new callback without opening storage`() = runTest {
        val admission = SeenReceiptAdmission()
        admission.fenceForPanic()
        val token = admission.capture()
        var providerCalls = 0
        var hooks = 0
        readReceiptCompletion(OLD_ID, token, { providerCalls++; error("Fenced storage must stay unopened") },
            onAccepted = { hooks++ })(true)
        assertNull(token)
        assertEquals(0, providerCalls)
        assertEquals(1, hooks)
    }

    @Test fun `old and other owner receipt tokens stay refused after verified resume`() = runTest {
        val fixture = Fixture()
        val old = fixture.admission.capture()
        val foreign = SeenReceiptAdmission().capture()
        assertTrue(fixture.clearForPanic())
        assertTrue(fixture.resumeSeen())
        assertFalse(fixture.seen.markReadReceiptSent(OLD_ID, old))
        assertFalse(fixture.seen.markReadReceiptSent(OLD_ID, foreign))
        assertTrue(fixture.seen.markReadReceiptSent(FRESH_ID, fixture.admission.capture()))
        assertEquals(expectedMap(FRESH_ID), fixture.storage.disk)
    }

    @Test fun `failed wipe cannot reopen receipt admission and an obsolete panic cannot resume its retry`() = runTest {
        val fixture = Fixture(seeded = true)
        fixture.storage.commitSucceeds = false
        val failed = fixture.admission.fenceForPanic()
        assertFalse(clearSeenForPanic(failed) { fixture.seen })
        assertFalse(fixture.admission.resumeAfterVerifiedPanic(failed))
        assertNull(fixture.admission.capture())
        fixture.storage.commitSucceeds = true
        val retry = fixture.admission.fenceForPanic()
        assertTrue(clearSeenForPanic(retry) { fixture.seen })
        assertFalse(fixture.admission.resumeAfterVerifiedPanic(failed))
        assertNull(fixture.admission.capture())
        assertTrue(fixture.admission.resumeAfterVerifiedPanic(retry))
        assertNotNull(fixture.admission.capture())
    }

    @Test fun `cancellation during checked Seen cleanup propagates and keeps admission fenced`() = runTest {
        val fixture = Fixture(seeded = true)
        fixture.storage.writeFailure = CancellationException("Synthetic canceled Seen mutation")
        var cancelled = false
        try { fixture.clearForPanic() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertNull(fixture.admission.capture())
        assertFalse(fixture.resumeSeen())
    }

    private fun TestScope.sender() = RetryingControlPacketSender(this,
        maxAttempts = 1, retryDelayMs = 0, interSendDelayMs = 0)

    private fun TestScope.holdFinalAttempt(fixture: Fixture, messageID: String,
        onAccepted: () -> Unit = {}): CompletableDeferred<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        sender().enqueue("synthetic-held:$messageID", sendAttempt = {
            entered.complete(Unit)
            release.await()
            true
        }, onComplete = readReceiptCompletion(messageID, fixture.admission.capture(), { fixture.seen }, onAccepted))
        runCurrent()
        assertTrue("the actual final retry attempt reached its controlled barrier", entered.isCompleted)
        return release
    }

    private fun TestScope.completeFresh(fixture: Fixture, messageID: String, onAccepted: () -> Unit = {}) {
        sender().enqueue("synthetic-fresh:$messageID", sendAttempt = { true },
            onComplete = readReceiptCompletion(messageID, fixture.admission.capture(), { fixture.seen }, onAccepted))
        runCurrent()
    }

    private suspend fun resume(seenCleared: Boolean, recovery: WeshRecoveryGate, fixture: Fixture? = null,
        otherPreferenceAttempt: () -> Unit = {}): Boolean = resumeAfterWeshPanicWipe(
        draftsCleared = true, conversationsCleared = true, mediaCleared = true,
        seenCleared = seenCleared,
        clearPreferences = { otherPreferenceAttempt(); true },
        resume = {
            if (fixture != null && !fixture.resumeSeen()) false else {
                recovery.normalPanicCompleted()
                true
            }
        },
        identityErasureConfirmed = true
    )

    private class Fixture(seeded: Boolean = false) {
        val storage = Storage(if (seeded) expectedMap(OLD_ID) else mapOf(NEIGHBOR to NEIGHBOR_VALUE))
        val admission = SeenReceiptAdmission()
        val seen = SeenMessageStore(SecureIdentityStateManager(storage.prefs, testOnly = true), admission, testOnly = true)
        private var panicToken: SeenReceiptAdmission.Token? = null

        suspend fun clearForPanic(): Boolean {
            val token = admission.fenceForPanic()
            panicToken = token
            return clearSeenForPanic(token) { seen }
        }

        fun resumeSeen(): Boolean = admission.resumeAfterVerifiedPanic(requireNotNull(panicToken))
    }

    /** Failed durable edits can still update the preference RAM cache. No backing domain exists. */
    private class Storage(initial: Map<String, String>) {
        val memory = initial.toMutableMap()
        val disk = initial.toMutableMap()
        val prefs: SharedPreferences = mock()
        var commitSucceeds = true
        var retainRemovedValues = false
        var writeFailure: Exception? = null

        init {
            whenever(prefs.getString(any(), anyOrNull())).thenAnswer {
                memory[it.getArgument<String>(0)] ?: it.getArgument<String?>(1)
            }
            whenever(prefs.contains(any())).thenAnswer { memory.containsKey(it.getArgument<String>(0)) }
            whenever(prefs.all).thenAnswer { memory.toMap() }
            whenever(prefs.edit()).thenAnswer {
                val editor: SharedPreferences.Editor = mock()
                val puts = mutableMapOf<String, String>()
                val removes = mutableSetOf<String>()
                whenever(editor.putString(any(), anyOrNull())).thenAnswer {
                    val key = it.getArgument<String>(0)
                    val value = it.getArgument<String?>(1)
                    if (value == null) removes += key else puts[key] = value
                    editor
                }
                whenever(editor.remove(any())).thenAnswer { removes += it.getArgument<String>(0); editor }
                fun publish() {
                    if (!retainRemovedValues) removes.forEach(memory::remove)
                    memory.putAll(puts)
                    writeFailure?.let { throw it }
                    if (commitSucceeds) { disk.clear(); disk.putAll(memory) }
                }
                whenever(editor.commit()).thenAnswer { publish(); commitSucceeds }
                doAnswer { publish(); null }.whenever(editor).apply()
                editor
            }
        }
    }

    private companion object {
        const val SEEN_KEY = "seen_message_store_v1"
        const val NEIGHBOR = "synthetic_unrelated_identity_value"
        const val NEIGHBOR_VALUE = "Synthetic retained preference"
        const val OLD_ID = "synthetic-old-read-receipt"
        const val FRESH_ID = "synthetic-fresh-read-receipt"

        fun expectedMap(vararg receiptIDs: String): Map<String, String> = mapOf(
            NEIGHBOR to NEIGHBOR_VALUE,
            SEEN_KEY to Gson().toJson(linkedMapOf(
                "delivered" to emptyList<String>(), "read" to emptyList<String>(),
                "read_receipts_sent" to receiptIDs.toList()
            ))
        )
    }
}
