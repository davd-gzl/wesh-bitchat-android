package com.bitchat.android.ui

import com.bitchat.android.services.SeenReceiptAdmission
import com.bitchat.android.startup.WeshRecoveryGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class PanicResetCoordinatorTest {
    @Test
    fun `a refused in-app erase still clears all app data`() = runTest {
        var cleared = 0
        PanicResetCoordinator.eraseOrClearAppData(
            erase = {
                PanicResetCoordinator.admitDestructivePanic(
                    establishIntent = { true },
                    retireOwner = { false },
                    eraseOwnedData = { error("a refused retirement never reaches the in-app wipe") }
                )
            },
            clearAppData = { cleared++ }
        )
        assertEquals(1, cleared)
    }

    @Test
    fun `a failed in-app erase or intent still clears all app data`() = runTest {
        var cleared = 0
        PanicResetCoordinator.eraseOrClearAppData(erase = { throw RestartFailure() }, clearAppData = { cleared++ })
        PanicResetCoordinator.eraseOrClearAppData(erase = { false }, clearAppData = { cleared++ })
        assertEquals(2, cleared)
    }

    @Test
    fun `a cancelled in-app erase still clears all app data`() = runTest {
        var cleared = 0
        val erasing = async {
            PanicResetCoordinator.eraseOrClearAppData(erase = { awaitCancellation() }, clearAppData = { cleared++ })
        }
        runCurrent()
        erasing.cancelAndJoin()
        assertEquals(1, cleared)
    }

    @Test
    fun `a completed in-app erase does not clear app data`() = runTest {
        var cleared = 0
        PanicResetCoordinator.eraseOrClearAppData(erase = { true }, clearAppData = { cleared++ })
        assertEquals(0, cleared)
    }

    @Test
    fun `start failure keeps durable recovery and all injected admission closed`() = runTest {
        val fixture = Fixture(this)
        fixture.beginPanic()
        var failed = false
        try {
            fixture.resume(start = { throw RestartFailure() })
        } catch (_: RestartFailure) {
            failed = true
        }
        assertTrue("the actual restart operation must fail", failed)
        assertEquals(1, fixture.starts)
        assertEquals(0, fixture.announces)
        fixture.assertRecoveryRetained()
    }

    @Test
    fun `announce failure keeps durable recovery and all injected admission closed`() = runTest {
        val fixture = Fixture(this)
        fixture.beginPanic()
        var failed = false
        try {
            fixture.resume(announce = { throw RestartFailure() })
        } catch (_: RestartFailure) {
            failed = true
        }
        assertTrue("the actual announcement operation must fail", failed)
        assertEquals(1, fixture.starts)
        assertEquals(1, fixture.announces)
        fixture.assertRecoveryRetained()
    }

    @Test
    fun `cancellation while restart is suspended retains recovery for the next startup`() = runTest {
        val fixture = Fixture(this)
        fixture.beginPanic()
        val enteredStart = CompletableDeferred<Unit>()
        val restarting = async {
            fixture.resume(start = {
                enteredStart.complete(Unit)
                awaitCancellation()
            })
        }
        try {
            withTimeout(5_000) { enteredStart.await() }
            assertFalse(restarting.isCompleted)
            restarting.cancelAndJoin()
            assertTrue(restarting.isCancelled)
            assertEquals(1, fixture.starts)
            assertEquals(0, fixture.announces)
            fixture.assertRecoveryRetained()
        } finally {
            restarting.cancelAndJoin()
        }
    }

    @Test
    fun `successful restart completes the real draft owner and opens admission once`() = runTest {
        val fixture = Fixture(this)
        fixture.beginPanic()

        assertTrue(fixture.resume())

        assertFalse(fixture.backend.pending)
        assertEquals(1, fixture.backend.finishes)
        assertFalse(fixture.recovery.recoveryRequired.value)
        assertFalse(fixture.privateWritesBlocked)
        assertFalse(fixture.preferenceWritesBlocked)
        assertTrue(fixture.coldTransportAllowed())
        assertTrue(fixture.seen.capture() != null)
        assertEquals(1, fixture.starts)
        assertEquals(1, fixture.announces)
        assertEquals(WeshDraftStore.Persistence.Confirmed, fixture.drafts.persistence.value)
        assertTrue(fixture.drafts.save(fixture.drafts.epoch, "public", "Synthetic fresh draft"))
    }

    @Test
    fun `seen refusal never opens recovery or starts transport`() = runTest {
        val fixture = Fixture(this)
        fixture.beginPanic()
        fixture.seen.confirmClear(fixture.seenPanic, false)

        assertFalse(fixture.resume())

        assertTrue(fixture.recovery.recoveryRequired.value)
        assertTrue(fixture.privateWritesBlocked)
        assertNull(fixture.seen.capture())
        assertEquals(0, fixture.starts)
        assertEquals(0, fixture.announces)
    }

    @Test
    fun `cancellation at fresh owner preparation cannot finish the marker or start transport`() = runTest {
        val fixture = Fixture(this)
        fixture.beginPanic()
        val prepared = CompletableDeferred<Unit>()
        val restarting = async {
            fixture.resume(afterPreparation = { prepared.complete(Unit); awaitCancellation() })
        }
        try {
            withTimeout(5_000) { prepared.await() }
            assertTrue(fixture.backend.pending)
            assertFalse(fixture.drafts.save(fixture.drafts.epoch, "public", "Synthetic blocked edit"))
            restarting.cancelAndJoin()
            fixture.assertRecoveryRetained()
            assertEquals(0, fixture.starts)
            assertEquals(0, fixture.backend.finishes)
        } finally {
            restarting.cancelAndJoin()
        }
    }

    @Test
    fun `restart and announce remain provisional with every writer fenced`() = runTest {
        val fixture = Fixture(this)
        fixture.beginPanic()
        fun assertProvisional() {
            fixture.assertRecoveryRetained()
            assertFalse(fixture.drafts.save(fixture.drafts.epoch, "public", "Synthetic blocked edit"))
        }
        assertTrue(fixture.resume(start = { assertProvisional() }, announce = { assertProvisional() }))
        assertFalse(fixture.backend.pending)
    }

    @Test
    fun `a draft generation superseded during restart cannot complete the marker`() = runTest {
        val fixture = Fixture(this)
        fixture.beginPanic()

        assertFalse(fixture.resume(announce = { fixture.drafts.fenceForPanic() }))

        fixture.assertRecoveryRetained()
        assertEquals(0, fixture.backend.finishes)
    }

    @Test
    fun `a Seen token superseded after preflight cannot finish the marker or reopen gates`() = runTest {
        val fixture = Fixture(this)
        fixture.beginPanic()
        lateinit var newerPanic: SeenReceiptAdmission.Token

        assertFalse(fixture.resume(announce = { newerPanic = fixture.seen.fenceForPanic() }))

        fixture.assertRecoveryRetained()
        assertTrue(fixture.seen.ownsPanic(newerPanic))
        assertFalse(fixture.seen.ownsPanic(fixture.seenPanic))
        assertEquals(0, fixture.backend.finishes)
    }

    @Test
    fun `backend finish exceptions keep recovery and draft writers fenced`() = runTest {
        val fixture = Fixture(this)
        fixture.beginPanic()
        fixture.backend.failFinish = true

        assertFalse(fixture.resume())

        fixture.assertRecoveryRetained()
        assertFalse(fixture.drafts.save(fixture.drafts.epoch, "public", "Synthetic blocked edit"))
        assertEquals(WeshDraftStore.Persistence.WipeRequired, fixture.drafts.persistence.value)
    }

    @Test
    fun `a sticky finish marker cannot reopen admission`() = runTest {
        val fixture = Fixture(this)
        fixture.beginPanic()
        fixture.backend.stickyMarker = true

        assertFalse(fixture.resume())

        fixture.assertRecoveryRetained()
        assertFalse(fixture.drafts.save(fixture.drafts.epoch, "public", "Synthetic blocked edit"))
    }

    @Test
    fun `unverified draft preparation never starts transport`() = runTest {
        val fixture = Fixture(this)
        fixture.beginPanic()
        fixture.drafts.fenceForPanic()

        assertFalse(fixture.resume())

        fixture.assertRecoveryRetained()
        assertEquals(0, fixture.starts)
        assertEquals(0, fixture.announces)
        assertEquals(0, fixture.backend.finishes)
    }

    @Test
    fun `queued ordinary load and old editor callback cannot bypass deferred global completion`() = runTest {
        val fixture = Fixture(this)
        fixture.drafts.load("11".repeat(32))
        val oldEditorEpoch = fixture.drafts.epoch
        fixture.beginPanic()
        val releaseOldCallback = CompletableDeferred<Unit>()
        val oldCallback = async {
            releaseOldCallback.await()
            fixture.drafts.save(oldEditorEpoch, "public", "Synthetic retired edit")
        }
        try {
            assertTrue(fixture.resume(start = {
                fixture.drafts.load("22".repeat(32))
                fixture.drafts.retry()
                assertTrue(fixture.backend.pending)
                assertEquals(0, fixture.backend.finishes)
                assertFalse(fixture.drafts.save(fixture.drafts.epoch, "public", "Synthetic early edit"))
            }))
            releaseOldCallback.complete(Unit)
            assertFalse(withTimeout(5_000) { oldCallback.await() })
            assertTrue(fixture.drafts.drafts.value.isEmpty())
            assertTrue(fixture.drafts.save(fixture.drafts.epoch, "public", "Synthetic new edit"))
            runCurrent()
            assertEquals(mapOf("public" to "Synthetic new edit"), fixture.backend.stored["22".repeat(32)])
            assertTrue(fixture.backend.stored["11".repeat(32)].isNullOrEmpty())
        } finally {
            oldCallback.cancelAndJoin()
        }
    }

    @Test
    fun `cancellation held after final IO commit observes one completed transaction`() = runTest {
        val fixture = Fixture(this)
        fixture.beginPanic()
        val caller = HeldReturnDispatcher(StandardTestDispatcher(testScheduler))
        fixture.backend.afterFinish = { caller.holdNextReturn() }
        val restarting = async(caller) { fixture.resume() }
        try {
            withTimeout(5_000) { caller.submitted.await() }
            assertFalse(restarting.isCompleted)
            // The actual IO operation and every synchronous gate change are already done.
            assertFalse(fixture.backend.pending)
            assertFalse(fixture.recovery.recoveryRequired.value)
            assertFalse(fixture.privateWritesBlocked)
            assertFalse(fixture.preferenceWritesBlocked)
            assertTrue(fixture.seen.capture() != null)
            restarting.cancel()
            caller.release()
            restarting.join()
            assertTrue(restarting.isCancelled)
            assertEquals(1, fixture.backend.finishes)
            assertEquals(1, fixture.starts)
            assertEquals(1, fixture.announces)
            assertFalse(fixture.backend.pending)
            assertFalse(fixture.recovery.recoveryRequired.value)
            assertFalse(fixture.privateWritesBlocked)
            assertFalse(fixture.preferenceWritesBlocked)
            assertTrue(fixture.seen.capture() != null)
        } finally {
            caller.release()
            restarting.cancelAndJoin()
        }
    }

    /** Captures the real withContext(IO) return instead of pausing a simulated helper. */
    private class HeldReturnDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        val submitted = CompletableDeferred<Unit>()
        private var holdNext = false
        private var held: Pair<CoroutineContext, Runnable>? = null
        fun holdNextReturn() { holdNext = true }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (holdNext) {
                holdNext = false
                check(held == null)
                held = context to block
                submitted.complete(Unit)
            } else delegate.dispatch(context, block)
        }
        fun release() {
            val pending = held ?: return
            held = null
            delegate.dispatch(pending.first, pending.second)
        }
    }

    private class RestartFailure : RuntimeException()

    private data class RecoveryState(
        val pendingMarker: Boolean,
        val runtimeBlocked: Boolean,
        val privateWritesBlocked: Boolean,
        val coldTransportAllowed: Boolean
    )

    /** No Context, platform storage, actual mesh, or process runtime is constructed. */
    private class Fixture(scope: TestScope) {
        val backend = MarkerBackend()
        val drafts = WeshDraftStore(backend, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler))
        val recovery = WeshRecoveryGate()
        val seen = SeenReceiptAdmission()
        lateinit var seenPanic: SeenReceiptAdmission.Token
        var privateWritesBlocked = true
        var preferenceWritesBlocked = true
        var starts = 0
        var announces = 0

        suspend fun beginPanic() {
            drafts.load("11".repeat(32))
            recovery.requireRecovery()
            seenPanic = seen.fenceForPanic()
            seen.confirmClear(seenPanic, true)
            assertTrue(drafts.panicClear(deferCompletion = true))
            assertTrue(backend.pending)
        }

        suspend fun resume(
            start: suspend () -> Unit = {},
            announce: suspend () -> Unit = {},
            afterPreparation: suspend () -> Unit = {}
        ): Boolean {
            var prepared: WeshDraftStore.PanicPreparation? = null
            return PanicResetCoordinator.resume(
                prepareFreshDrafts = {
                    prepared = drafts.prepareAfterVerifiedPanic("22".repeat(32))
                    afterPreparation()
                    prepared != null
                },
                seenReady = { seen.canResumeAfterVerifiedPanic(seenPanic) },
                startTransport = { starts++; start() },
                announce = { announces++; announce() },
                complete = {
                    prepared?.let { PanicResetCoordinator.complete(drafts, it, seenPanic) {
                        preferenceWritesBlocked = false
                        privateWritesBlocked = false
                        recovery.normalPanicCompleted()
                    } } ?: false
                },
                retainSeenFence = { seen.retainFenceAfterFailure(seenPanic) }
            )
        }

        fun coldTransportAllowed(): Boolean {
            val coldGate = WeshRecoveryGate()
            if (backend.pending) coldGate.requireRecovery()
            return coldGate.allowsTransport(prepared = true)
        }

        fun assertRecoveryRetained() {
            assertTrue("preference writers remain fenced until the terminal commit", preferenceWritesBlocked)
            assertNull("receipt-only failure cleanup must remain closed", seen.capture())
            assertEquals(
                "restart failure must retain the durable marker and every injected admission gate",
                RecoveryState(true, true, true, false),
                RecoveryState(backend.pending, recovery.recoveryRequired.value, privateWritesBlocked, coldTransportAllowed())
            )
        }
    }

    private class MarkerBackend : WeshDraftBackend {
        var pending = false
        var finishes = 0
        var failFinish = false
        var stickyMarker = false
        var afterFinish: () -> Unit = {}
        val stored = linkedMapOf<String, Map<String, String>>()
        override fun load(owner: String): Map<String, String> {
            if (pending) throw DraftWipePending()
            return stored[owner].orEmpty()
        }
        override fun write(owner: String, drafts: Map<String, String>) {
            check(!pending)
            stored[owner] = drafts.toMap()
        }
        override fun markPanicPending() { pending = true }
        override fun wipe() { pending = true; stored.clear() }
        override fun finishVerifiedWipe() {
            check(pending && !failFinish)
            finishes++
            stored.clear()
            if (!stickyMarker) pending = false
            check(!pending)
            afterFinish()
        }
    }
}
