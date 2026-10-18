package com.bitchat.android.mesh

import android.app.Application
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationStorageCipher
import com.bitchat.android.services.SeenReceiptAdmission
import com.bitchat.android.startup.WeshRecoveryGate
import com.bitchat.android.ui.EncryptedWeshDraftBackend
import com.bitchat.android.ui.PanicResetCoordinator
import com.bitchat.android.ui.WeshDraftStore
import com.bitchat.android.ui.resumeAfterWeshPanicWipe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/** Actual facade and owned announcement job, with RAM transport/clock ports and no Context. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class UnifiedMeshOwnerRetirementTest {
    @get:Rule val intentDirectory = TemporaryFolder()
    @Before fun setUp() { AppStateStore.clear() }
    @After fun tearDown() { AppStateStore.clear() }

    @Test fun `transport start cannot hold facade monitor while an external holder refreshes delegates`() = runTest {
        withFixture { f ->
            f.facade.delegate = f.ui
            val externalHolder = ReentrantLock()
            val holderLocked = CountDownLatch(1)
            val startPortEntered = CountDownLatch(1)
            val refreshRequested = CountDownLatch(1)
            val acquiredExternalLock = AtomicBoolean(false)
            val workerErrors = ConcurrentLinkedQueue<Throwable>()
            f.ble.startAction = {
                try {
                    startPortEntered.countDown()
                    check(refreshRequested.await(5, TimeUnit.SECONDS)) { "Synthetic holder did not request refresh" }
                    // A timed acquisition breaks the old lock cycle so even RED can reap both workers.
                    if (externalHolder.tryLock(2, TimeUnit.SECONDS)) {
                        try { acquiredExternalLock.set(true) } finally { externalHolder.unlock() }
                    }
                } catch (failure: Throwable) {
                    // The facade catches transport exceptions; retain fixture failures independently.
                    workerErrors.add(failure)
                    throw failure
                }
            }
            val holderThread = Thread({
                try {
                    externalHolder.lockInterruptibly()
                    try {
                        holderLocked.countDown()
                        check(startPortEntered.await(5, TimeUnit.SECONDS)) { "Synthetic BLE start did not enter" }
                        refreshRequested.countDown()
                        f.facade.refreshDelegates()
                    } finally {
                        externalHolder.unlock()
                    }
                } catch (failure: Throwable) {
                    workerErrors.add(failure)
                }
            }, "synthetic-holder-refresh").apply { isDaemon = true }
            val startThread = Thread({
                try {
                    check(holderLocked.await(5, TimeUnit.SECONDS)) { "Synthetic holder did not acquire its lock" }
                    f.facade.startServices()
                } catch (failure: Throwable) {
                    workerErrors.add(failure)
                }
            }, "synthetic-facade-start").apply { isDaemon = true }
            var completedBeforeCleanup = false
            try {
                holderThread.start()
                startThread.start()
                startThread.join(7_000)
                holderThread.join(7_000)
                completedBeforeCleanup = !startThread.isAlive && !holderThread.isAlive
            } finally {
                holderLocked.countDown()
                startPortEntered.countDown()
                refreshRequested.countDown()
                startThread.interrupt()
                holderThread.interrupt()
                startThread.join(5_000)
                holderThread.join(5_000)
            }
            assertTrue("workers must finish before forced escape cleanup", completedBeforeCleanup)
            assertFalse("owned synthetic start thread must exit", startThread.isAlive)
            assertFalse("owned synthetic holder thread must exit", holderThread.isAlive)
            assertTrue("synthetic workers must finish without errors: $workerErrors", workerErrors.isEmpty())
            assertTrue("transport dispatch must release facade admission before acquiring the external holder lock",
                acquiredExternalLock.get())
        }
    }

    @Test fun `concurrent retirement waits held dispatch then detaches and erases only captured owners`() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        withFixture(beforeClose = { release.countDown() }) { f ->
            f.facade.delegate = f.ui
            val portReturned = AtomicBoolean(false)
            val detachedTooEarly = AtomicBoolean(false)
            val detachCount = AtomicInteger()
            val wifiReads = AtomicInteger()
            val workerErrors = ConcurrentLinkedQueue<Throwable>()
            f.currentWifiRead = { wifiReads.incrementAndGet() }
            f.wifiA.delegateAction = { value ->
                if (value == null) {
                    if (!portReturned.get()) detachedTooEarly.set(true)
                    detachCount.incrementAndGet()
                }
            }
            f.ble.startAction = {
                try {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Synthetic held start was not released" }
                } catch (failure: Throwable) {
                    workerErrors.add(failure)
                    throw failure
                } finally {
                    portReturned.set(true)
                }
            }
            val worker = Thread({
                try { f.facade.startServices() } catch (failure: Throwable) { workerErrors.add(failure) }
            }, "synthetic-held-dispatch").apply { isDaemon = true }
            var first: Deferred<Boolean>? = null
            var second: Deferred<Boolean>? = null
            try {
                worker.start()
                assertTrue("actual synchronous transport dispatch must reach its barrier", entered.await(5, TimeUnit.SECONDS))
                val firstRequest = async { f.facade.retireForPanic() }
                val secondRequest = async { f.facade.retireForPanic() }
                first = firstRequest
                second = secondRequest
                runCurrent()
                assertFalse("first retirement must await admitted dispatch", firstRequest.isCompleted)
                assertFalse("concurrent retirement must await the same completion", secondRequest.isCompleted)
                assertEquals("retirement snapshots WiFi once", 1, wifiReads.get())
                assertEquals(0, detachCount.get())
                val before = f.events()
                f.facade.startServices()
                f.facade.sendMessage("Synthetic refused public", emptyList())
                f.facade.sendVerifyChallenge(PEER, KEY_HEX, ByteArray(16) { 3 })
                assertEquals("new dispatch is fenced while prior dispatch is held", before, f.events())
                val differentFacade = RecordingDelegate()
                val replacement = FakeMesh("replacement").apply { delegate = differentFacade }
                f.wifi = replacement
                // Final cleanup must preserve an owner installed by somebody else.
                f.ble.delegate = differentFacade
                release.countDown()
                worker.join(5_000)
                assertFalse("held dispatch worker must exit", worker.isAlive)
                assertTrue(withTimeout(1_000) { firstRequest.await() })
                assertTrue(withTimeout(1_000) { secondRequest.await() })
                assertTrue(portReturned.get())
                assertFalse(detachedTooEarly.get())
                assertEquals("single retirement detaches captured WiFi once", 1, detachCount.get())
                assertNull(f.wifiA.delegate)
                assertSame(differentFacade, f.ble.delegate)
                assertSame(differentFacade, replacement.delegate)
                assertTrue("held BLE start must not continue into a later WiFi start phase", f.portCalls.isEmpty())
                assertFalse(f.ownerScope.isActive)
                f.facade.clearAllInternalData()
                f.facade.clearAllEncryptionData()
                assertEquals(1, f.ble.calls.count { it == "erase-state" })
                assertEquals(1, f.ble.calls.count { it == "erase-crypto" })
                assertEquals(1, f.wifiA.calls.count { it == "erase-state" })
                assertEquals(1, f.wifiA.calls.count { it == "erase-crypto" })
                assertTrue(replacement.calls.isEmpty())
                assertEquals("no provider lookup after fencing or during either eraser", 1, wifiReads.get())
            } finally {
                release.countDown()
                worker.interrupt()
                worker.join(5_000)
                assertFalse("owned held-dispatch worker must be reaped", worker.isAlive)
                withTimeout(1_000) {
                    first?.cancelAndJoin()
                    second?.cancelAndJoin()
                }
            }
            assertTrue("synthetic worker must finish without errors: $workerErrors", workerErrors.isEmpty())
        }
    }

    @Test fun `retirement before start permanently refuses later transport starts`() = runTest {
        withFixture { f ->
            assertTrue(f.facade.retireForPanic())
            val before = f.events()
            f.facade.startServices()
            runCurrent()
            assertEquals("an obsolete facade must never start either transport", before, f.events())
            assertFalse(f.ownerScope.isActive)
        }
    }

    @Test fun `retired facade cannot dispatch public private bound or receipt sends`() = runTest {
        withFixture { f ->
            f.facade.delegate = f.ui
            assertTrue(f.facade.retireForPanic())
            val before = f.events()
            f.facade.sendMessage("Synthetic public", emptyList())
            f.facade.sendPrivateMessage("Synthetic private", PEER, "Synthetic peer", MESSAGE_ID)
            val boundAccepted = f.facade.sendPrivateMessageBound("Synthetic bound", PEER, ByteArray(32) { 7 }, MESSAGE_ID) { true }
            f.facade.sendReadReceipt(MESSAGE_ID, PEER, "Synthetic reader")
            f.facade.sendBroadcastAnnounce()
            f.facade.sendAnnouncementToPeer(PEER)
            assertEquals(before, f.events())
            assertFalse(boundAccepted)
        }
    }

    @Test fun `bound send awaiting authorization cannot outlive its facade owner`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withFixture(beforeClose = { release.complete(Unit) }) { f ->
            f.ble.boundSend = { authorized -> entered.complete(Unit); release.await(); authorized() }
            val pending = async {
                f.facade.sendPrivateMessageBound("Synthetic held bound", PEER, ByteArray(32) { 7 }, MESSAGE_ID) { true }
            }
            try {
                runCurrent()
                assertTrue("the actual facade forwarded the held authorization", entered.isCompleted)
                assertTrue(f.facade.retireForPanic())
                release.complete(Unit)
                assertFalse("retiring the facade also invalidates its previously forwarded authorization",
                    withTimeout(1_000) { pending.await() })
            } finally {
                release.complete(Unit)
                withTimeout(1_000) { pending.cancelAndJoin() }
            }
        }
    }

    @Test fun `retired facade cannot verify handshake favorite or update peers`() = runTest {
        withFixture { f ->
            f.facade.delegate = f.ui
            assertTrue(f.facade.retireForPanic())
            val before = f.events()
            f.facade.sendVerifyChallenge(PEER, KEY_HEX, ByteArray(16) { 3 })
            f.facade.sendVerifyResponse(PEER, KEY_HEX, ByteArray(16) { 3 })
            f.facade.initiateNoiseHandshake(PEER)
            f.facade.sendFavoriteNotification(PEER, true)
            val updated = f.facade.updatePeerInfo(PEER, "Synthetic peer", ByteArray(32) { 7 }, ByteArray(32) { 8 }, true)
            assertEquals(before, f.events())
            assertFalse(updated)
        }
    }

    @Test fun `captured old delegate callbacks cannot reach UI after retirement`() = runTest {
        withFixture { f ->
            f.facade.delegate = f.ui
            val oldDelegate = requireNotNull(f.ble.delegate)
            assertTrue(f.facade.retireForPanic())
            oldDelegate.didReceiveMessage(message())
            oldDelegate.didUpdatePeerList(listOf(PEER))
            oldDelegate.didReceiveDeliveryAck(MESSAGE_ID, PEER)
            oldDelegate.didReceiveReadReceipt(MESSAGE_ID, PEER)
            oldDelegate.didReceiveVerifyChallenge(PEER, byteArrayOf(1), 1)
            oldDelegate.didReceiveVerifyResponse(PEER, byteArrayOf(2), 2)
            assertTrue("already captured owner callbacks must stay fenced", f.ui.events.isEmpty())
            assertNull(oldDelegate.getNickname())
            assertFalse(oldDelegate.isFavorite(PEER))
            assertNull(f.ble.delegate)
            assertNull(f.wifiA.delegate)
        }
    }

    @Test fun `retired facade does not bind a replacement WiFi owner`() = runTest {
        withFixture { f ->
            f.facade.delegate = f.ui
            assertTrue(f.facade.retireForPanic())
            val replacement = FakeMesh("replacement")
            f.wifi = replacement
            val replacementBefore = replacement.calls.toList()
            f.facade.refreshDelegates()
            f.facade.getPeerNicknames()
            assertNull("an old facade must not install itself on the current owner's WiFi", replacement.delegate)
            assertEquals(replacementBefore, replacement.calls)
        }
    }

    @Test fun `retired facade rejects later delegate assignment`() = runTest {
        withFixture { f ->
            assertTrue(f.facade.retireForPanic())
            val lateUi = RecordingDelegate()
            f.facade.delegate = lateUi
            f.facade.didReceiveMessage(message())
            assertNull(f.facade.delegate)
            assertNull(f.ble.delegate)
            assertNull(f.wifiA.delegate)
            assertTrue(lateUi.events.isEmpty())
        }
    }

    @Test fun `retirement awaits the actual announcement collector completion`() = runTest {
        val cancelling = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withFixture(heldProfile(cancelling, release), beforeClose = { release.complete(Unit) }) { f ->
            f.facade.startServices()
            runCurrent()
            advanceTimeBy(10)
            runCurrent()
            assertEquals(1, f.ble.calls.count { it == "announce" })
            val pending = async { f.facade.retireForPanic() }
            try {
                runCurrent()
                assertTrue("the real owned collector must receive cancellation", cancelling.isCompleted)
                assertFalse("retirement must await its held completion, not just cancel", pending.isCompleted)
                release.complete(Unit)
                assertTrue(withTimeout(1_000) { pending.await() })
                assertFalse(f.ownerScope.isActive)
            } finally {
                release.complete(Unit)
                withTimeout(1_000) { pending.cancelAndJoin() }
            }
        }
    }

    @Test fun `caller cancellation cannot reopen facade while collector cleanup is held`() = runTest {
        val cancelling = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withFixture(heldProfile(cancelling, release), beforeClose = { release.complete(Unit) }) { f ->
            f.facade.startServices()
            runCurrent()
            val pending = async { f.facade.retireForPanic() }
            try {
                runCurrent()
                pending.cancel()
                runCurrent()
                val before = f.events()
                f.facade.startServices()
                f.facade.sendBroadcastAnnounce()
                assertEquals("caller cancellation must not revive old work", before, f.events())
                assertTrue(cancelling.isCompleted)
                assertFalse("cleanup remains owned until the actual collector exits", pending.isCompleted)
                release.complete(Unit)
                withTimeout(1_000) { pending.join() }
                assertTrue(f.facade.retireForPanic())
                assertFalse(f.ownerScope.isActive)
            } finally {
                release.complete(Unit)
                withTimeout(1_000) { pending.cancelAndJoin() }
            }
        }
    }

    @Test fun `panic erasure keeps the captured WiFi handle after replacement and repeated retirement`() = runTest {
        withFixture { f ->
            f.facade.delegate = f.ui
            assertTrue(f.facade.retireForPanic())
            val replacement = FakeMesh("replacement")
            f.wifi = replacement
            assertTrue(f.facade.retireForPanic())
            f.facade.clearAllInternalData()
            f.facade.clearAllEncryptionData()
            assertEquals(1, f.ble.calls.count { it == "erase-state" })
            assertEquals(1, f.ble.calls.count { it == "erase-crypto" })
            assertEquals("state erasure still reaches the old owner", 1, f.wifiA.calls.count { it == "erase-state" })
            assertEquals("identity erasure still reaches the old owner", 1, f.wifiA.calls.count { it == "erase-crypto" })
            assertTrue("retirement must never erase the replacement owner's identity", replacement.calls.isEmpty())
        }
    }

    @Test fun `retirement with no WiFi must not erase a future owner`() = runTest {
        withFixture { f ->
            f.wifi = null
            assertTrue(f.facade.retireForPanic())
            val replacement = FakeMesh("replacement")
            f.wifi = replacement
            f.facade.clearAllInternalData()
            f.facade.clearAllEncryptionData()
            assertEquals(1, f.ble.calls.count { it == "erase-state" })
            assertEquals(1, f.ble.calls.count { it == "erase-crypto" })
            assertTrue(replacement.calls.isEmpty())
        }
    }

    @Test fun `unconfirmed facade retirement blocks the real panic continuation`() = runTest {
        var preferences = 0
        var resumes = 0
        val result = resumeAfterWeshPanicWipe(
            draftsCleared = true, conversationsCleared = true, mediaCleared = true,
            seenCleared = true, identityErasureConfirmed = true, meshOwnerRetired = false,
            clearPreferences = { preferences++; true }, resume = { resumes++; true }
        )
        assertEquals("independent preference cleanup still runs", 1, preferences)
        assertFalse("retirement is a mandatory result, not an optional hint", result)
        assertEquals(0, resumes)
    }

    @Test fun `ordinary start stop keeps transport dispatch and nonterminal facade scope`() = runTest {
        withFixture { f ->
            f.facade.delegate = f.ui
            f.facade.startServices()
            runCurrent()
            assertEquals(1, f.ble.calls.count { it == "start" })
            assertEquals(listOf("start-wifi"), f.portCalls)
            assertSame(f.facade, f.ble.delegate)
            assertSame(f.facade, f.wifiA.delegate)
            val activeDelegate = requireNotNull(f.ble.delegate)
            activeDelegate.didReceiveMessage(message())
            activeDelegate.didUpdatePeerList(listOf(PEER))
            activeDelegate.didReceiveDeliveryAck(MESSAGE_ID, PEER)
            activeDelegate.didReceiveReadReceipt(MESSAGE_ID, PEER)
            activeDelegate.didReceiveVerifyChallenge(PEER, byteArrayOf(1), 1)
            activeDelegate.didReceiveVerifyResponse(PEER, byteArrayOf(2), 2)
            assertEquals(listOf("message", "peers", "delivered", "read", "challenge", "response"), f.ui.events)
            assertEquals("Synthetic local nickname", activeDelegate.getNickname())
            assertTrue(activeDelegate.isFavorite(PEER))
            f.facade.stopServices()
            runCurrent()
            assertEquals(1, f.ble.calls.count { it == "stop" })
            assertEquals(listOf("start-wifi", "stop-wifi"), f.portCalls)
            assertTrue("ordinary stop must not become panic retirement", f.ownerScope.isActive)
            val announces = f.ble.calls.count { it == "announce" }
            advanceTimeBy(100)
            runCurrent()
            assertEquals(announces, f.ble.calls.count { it == "announce" })
        }
    }

    @Test fun `ordinary BLE routing and identity getters remain unchanged`() = runTest {
        withFixture { f ->
            f.facade.sendMessage("Synthetic public", listOf("Synthetic mention"))
            f.facade.sendPrivateMessage("Synthetic private", PEER, "Synthetic peer", MESSAGE_ID)
            assertTrue(f.facade.sendPrivateMessageBound("Synthetic bound", PEER, ByteArray(32) { 7 }, MESSAGE_ID) { true })
            f.facade.sendReadReceipt(MESSAGE_ID, PEER, "Synthetic reader")
            f.facade.sendVerifyChallenge(PEER, KEY_HEX, ByteArray(16) { 3 })
            f.facade.sendVerifyResponse(PEER, KEY_HEX, ByteArray(16) { 3 })
            assertEquals(listOf("public", "private", "bound", "read", "challenge", "response"), f.ble.calls)
            assertTrue(f.wifiA.calls.isEmpty())
            assertEquals(PEER, f.facade.myPeerID)
            assertEquals("synthetic-fingerprint", f.facade.getIdentityFingerprint())
            assertArrayEquals(ByteArray(32) { 7 }, f.facade.getStaticNoisePublicKey())
            assertArrayEquals(ByteArray(32) { 7 }, f.facade.getAuthenticatedRemoteStaticKey(PEER))
            assertTrue(f.facade.hasEstablishedSession(PEER))
        }
    }

    @Test fun `ordinary WiFi routing and disabled BLE adapter remain unchanged`() = runTest {
        withFixture { f ->
            f.bleEnabled = false
            f.ble.connected = false
            f.facade.startServices()
            runCurrent()
            f.facade.sendMessage("Synthetic public", emptyList())
            f.facade.sendPrivateMessage("Synthetic private", PEER, "Synthetic peer", MESSAGE_ID)
            f.facade.sendReadReceipt(MESSAGE_ID, PEER, "Synthetic reader")
            f.facade.sendVerifyChallenge(PEER, KEY_HEX, ByteArray(16) { 3 })
            f.facade.sendVerifyResponse(PEER, KEY_HEX, ByteArray(16) { 3 })
            assertFalse(f.ble.calls.contains("start"))
            assertEquals(listOf("disable-ble", "start-wifi"), f.portCalls)
            assertEquals(listOf("public", "private", "read", "challenge", "response"), f.wifiA.calls)
        }
    }

    @Test fun `ordinary announcement cadence waits full interval and honors recipient changes`() = runTest {
        withFixture { f ->
            f.facade.startServices()
            runCurrent()
            advanceTimeBy(9)
            runCurrent()
            assertFalse(f.ble.calls.contains("announce"))
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, f.ble.calls.count { it == "announce" })
            f.profile.value = 5L to false
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(1, f.ble.calls.count { it == "announce" })
            f.profile.value = 5L to true
            runCurrent()
            advanceTimeBy(4)
            runCurrent()
            assertEquals(1, f.ble.calls.count { it == "announce" })
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, f.ble.calls.count { it == "announce" })
        }
    }

    @Test fun `ordinary erasure still follows current WiFi before any retirement`() = runTest {
        withFixture { f ->
            val replacement = FakeMesh("replacement")
            f.wifi = replacement
            f.facade.clearAllInternalData()
            f.facade.clearAllEncryptionData()
            assertEquals(listOf("erase-state", "erase-crypto"), f.ble.calls)
            assertTrue(f.wifiA.calls.isEmpty())
            assertEquals(listOf("erase-state", "erase-crypto"), replacement.calls)
        }
    }

    @Test fun `fresh facade can start and complete the real panic continuation`() = runTest {
        withFixture { old ->
            assertTrue(old.facade.retireForPanic())
            withFixture { fresh ->
                var preferences = 0
                val result = resumeAfterWeshPanicWipe(
                    draftsCleared = true, conversationsCleared = true, mediaCleared = true,
                    seenCleared = true, identityErasureConfirmed = true, meshOwnerRetired = true,
                    clearPreferences = { preferences++; true },
                    resume = { fresh.facade.startServices(); true }
                )
                runCurrent()
                assertTrue(result)
                assertEquals(1, preferences)
                assertEquals(1, fresh.ble.calls.count { it == "start" })
                assertEquals(listOf("start-wifi"), fresh.portCalls)
            }
        }
    }

    @Test fun `synchronous preparation cannot consume the only checked drain leader`() = runTest {
        withFixture { f ->
            f.facade.delegate = f.ui
            val oldDelegate = requireNotNull(f.ble.delegate)
            f.facade.fenceForPanic()
            assertTrue("preparation alone never cancels the scope", f.ownerScope.isActive)
            val before = f.events()
            f.facade.startServices()
            f.facade.sendMessage("Synthetic refused after preparation", emptyList())
            oldDelegate.didReceiveMessage(message())
            assertEquals(before, f.events())
            assertTrue(f.ui.events.isEmpty())
            val first = async { f.facade.retireForPanic() }
            val second = async { f.facade.retireForPanic() }
            try {
                assertTrue(withTimeout(1_000) { first.await() })
                assertTrue(withTimeout(1_000) { second.await() })
                assertFalse(f.ownerScope.isActive)
            } finally {
                withTimeout(1_000) { first.cancelAndJoin(); second.cancelAndJoin() }
            }
        }
    }

    @Test fun `actual held facade drain already has verified durable intent without key erasure`() = runTest {
        heldIntentContract(cancelRequest = false)
    }

    @Test fun `caller cancellation during actual held drain retains durable intent`() = runTest {
        heldIntentContract(cancelRequest = true)
    }

    private suspend fun TestScope.heldIntentContract(cancelRequest: Boolean) {
        val cancelling = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withFixture(heldProfile(cancelling, release), beforeClose = { release.complete(Unit) }) { f ->
            f.facade.startServices()
            runCurrent()
            val root = intentDirectory.newFolder()
            val data = File(root, "wesh-drafts-v1.bin")
            val opaque = "Synthetic opaque draft sentinel".toByteArray()
            data.writeBytes(opaque)
            val destroys = AtomicInteger()
            val backend = EncryptedWeshDraftBackend(root, object : ConversationStorageCipher {
                override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = error("Unexpected cipher access")
                override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray = error("Unexpected cipher access")
                override fun destroyKey() { destroys.incrementAndGet() }
            })
            val drafts = WeshDraftStore(backend, ioDispatcher = StandardTestDispatcher(testScheduler))
            drafts.fenceForPanic()
            f.facade.fenceForPanic()
            val recovery = WeshRecoveryGate().also { it.requireRecovery() }
            val seen = SeenReceiptAdmission().also { it.fenceForPanic() }
            var erases = 0
            val request = async {
                PanicResetCoordinator.admitDestructivePanic(
                    establishIntent = { drafts.establishPanicIntent(drafts.epoch) },
                    retireOwner = { f.facade.retireForPanic() },
                    eraseOwnedData = { retired -> erases++; drafts.panicClear(deferCompletion = true) && retired }
                )
            }
            var primaryFailure: Throwable? = null
            try {
                withTimeout(1_000) { cancelling.await() }
                if (cancelRequest) request.cancel()
                assertFalse("the actual owned collector has not completed", request.isCompleted)
                val marker = File(root, "wesh-drafts-wipe-v1")
                assertTrue("durable intent must precede the held owner drain", marker.isFile)
                assertArrayEquals(byteArrayOf(1), marker.readBytes())
                val cold = WeshRecoveryGate()
                if (EncryptedWeshDraftBackend.hasPendingWipe(root)) cold.requireRecovery()
                assertFalse(cold.allowsTransport(prepared = true))
                assertFalse(recovery.allowsTransport(prepared = true))
                assertNull(seen.capture())
                assertFalse(drafts.save(drafts.epoch, "public", "Synthetic refused edit"))
                assertArrayEquals(opaque, data.readBytes())
                assertEquals(0, destroys.get())
                assertEquals(0, erases)
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                try {
                    request.cancel()
                    release.complete(Unit)
                    withTimeout(1_000) { request.cancelAndJoin() }
                } catch (cleanupFailure: Throwable) {
                    val original = primaryFailure
                    if (original != null) original.addSuppressed(cleanupFailure) else throw cleanupFailure
                }
            }
            if (cancelRequest) {
                assertTrue("the cancelled request has completed after the actual drain", request.isCancelled && request.isCompleted)
                val marker = File(root, "wesh-drafts-wipe-v1")
                assertTrue("cancellation after drain must retain durable intent", marker.isFile)
                assertArrayEquals(byteArrayOf(1), marker.readBytes())
                assertTrue(EncryptedWeshDraftBackend.hasPendingWipe(root))
                val cold = WeshRecoveryGate()
                if (EncryptedWeshDraftBackend.hasPendingWipe(root)) cold.requireRecovery()
                assertFalse(cold.allowsTransport(prepared = true))
                assertFalse(recovery.allowsTransport(prepared = true))
                assertNull(seen.capture())
                assertFalse(drafts.save(drafts.epoch, "public", "Synthetic refused edit after cancellation"))
                assertArrayEquals(opaque, data.readBytes())
                assertEquals(0, destroys.get())
                assertEquals(0, erases)
            }
        }
    }

    @Test fun `cancellation before request dispatch cannot skip durable intent`() = runTest {
        withFixture { f ->
            val root = intentDirectory.newFolder()
            val destroys = AtomicInteger()
            val backend = EncryptedWeshDraftBackend(root, object : ConversationStorageCipher {
                override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = error("Unexpected cipher access")
                override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray = error("Unexpected cipher access")
                override fun destroyKey() { destroys.incrementAndGet() }
            })
            val drafts = WeshDraftStore(backend, ioDispatcher = StandardTestDispatcher(testScheduler))
            drafts.fenceForPanic()
            f.facade.fenceForPanic()
            val requestOwner = SupervisorJob()
            val scope = CoroutineScope(requestOwner + StandardTestDispatcher(testScheduler))
            requestOwner.cancel()
            var ownerCalls = 0
            var erases = 0
            val request = PanicResetCoordinator.launchPanic(scope) {
                PanicResetCoordinator.admitDestructivePanic(
                    establishIntent = { drafts.establishPanicIntent(drafts.epoch) },
                    retireOwner = { ownerCalls++; f.facade.retireForPanic() },
                    eraseOwnedData = { erases++; true }
                )
            }
            try {
                runCurrent()
                withTimeout(1_000) { request.join() }
                assertTrue(request.isCancelled)
                assertTrue("a cancelled dispatch still records the already requested panic", File(root, "wesh-drafts-wipe-v1").isFile)
                assertTrue(EncryptedWeshDraftBackend.hasPendingWipe(root))
                assertEquals(0, ownerCalls)
                assertEquals(0, erases)
                assertEquals(0, destroys.get())
                assertFalse(drafts.save(drafts.epoch, "public", "Synthetic refused edit"))
            } finally {
                withTimeout(1_000) { request.cancelAndJoin(); requestOwner.cancelAndJoin() }
            }
        }
    }

    private fun heldProfile(cancelling: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) = flow {
        try {
            emit(10L to true)
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                cancelling.complete(Unit)
                release.await()
            }
        }
    }

    private suspend fun TestScope.withFixture(profile: Flow<Pair<Long, Boolean>>? = null,
        beforeClose: () -> Unit = {}, block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(this, profile)
        try {
            block(fixture)
        } finally {
            beforeClose()
            withTimeout(1_000) { fixture.ownerJob.cancelAndJoin() }
        }
    }

    private class Fixture(scope: TestScope, profileOverride: Flow<Pair<Long, Boolean>>?) {
        val ownerJob = SupervisorJob()
        val ownerScope = CoroutineScope(ownerJob + StandardTestDispatcher(scope.testScheduler))
        val profile = MutableStateFlow(10L to true)
        val ble = FakeMesh("ble")
        val wifiA = FakeMesh("wifi-a")
        var wifi: MeshService? = wifiA
        var currentWifiRead: (() -> Unit)? = null
        var bleEnabled = true
        val portCalls = mutableListOf<String>()
        val ui = RecordingDelegate()
        val facade = UnifiedMeshService(ble, UnifiedMeshPorts(
            scope = ownerScope,
            announcementProfile = profileOverride ?: profile,
            hasAnnouncementRecipients = { profile.value.second },
            currentWifi = { currentWifiRead?.invoke(); wifi },
            startWifi = { portCalls += "start-wifi" },
            stopWifi = { portCalls += "stop-wifi" },
            bleEnabled = { bleEnabled },
            disableBle = { portCalls += "disable-ble" },
            currentNpub = { null }
        ))

        fun events() = listOf(ble.calls.toList(), wifiA.calls.toList(), portCalls.toList())
    }

    private class RecordingDelegate : MeshDelegate {
        val events = mutableListOf<String>()
        override fun didReceiveMessage(message: BitchatMessage) { events += "message" }
        override fun didUpdatePeerList(peers: List<String>) { events += "peers" }
        override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) { events += "delivered" }
        override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) { events += "read" }
        override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) { events += "challenge" }
        override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) { events += "response" }
        override fun getNickname() = "Synthetic local nickname"
        override fun isFavorite(peerID: String) = true
    }

    private class FakeMesh(val label: String) : MeshService {
        val calls = mutableListOf<String>()
        var connected = true
        var startAction: (() -> Unit)? = null
        var boundSend: (suspend (() -> Boolean) -> Boolean)? = null
        override val myPeerID = PEER
        var delegateAction: ((MeshDelegate?) -> Unit)? = null
        override var delegate: MeshDelegate? = null
            set(value) { field = value; delegateAction?.invoke(value) }
        override fun startServices() { calls += "start"; startAction?.invoke() }
        override fun stopServices() { calls += "stop" }
        override fun sendMessage(content: String, mentions: List<String>) { calls += "public" }
        override fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String?) { calls += "private" }
        override suspend fun sendPrivateMessageBound(content: String, recipientPeerID: String, expectedKey: ByteArray,
            messageID: String, authorized: () -> Boolean): Boolean {
            calls += "bound"
            return boundSend?.invoke(authorized) ?: authorized()
        }
        override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) { calls += "read" }
        override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) { calls += "challenge" }
        override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) { calls += "response" }
        override fun sendBroadcastAnnounce() { calls += "announce" }
        override fun sendAnnouncementToPeer(peerID: String) { calls += "announce-peer" }
        override fun getPeerNicknames() = mapOf(PEER to "Synthetic peer")
        override fun getPeerRSSI() = emptyMap<String, Int>()
        override fun getActivePeerCount() = if (connected) 1 else 0
        override fun hasEstablishedSession(peerID: String) = connected
        override fun getSessionState(peerID: String) = if (connected) NoiseSession.NoiseSessionState.Established else NoiseSession.NoiseSessionState.Uninitialized
        override fun initiateNoiseHandshake(peerID: String) { calls += "handshake" }
        override fun getPeerFingerprint(peerID: String) = "synthetic-fingerprint"
        override fun getPeerInfo(peerID: String) = PeerInfo(PEER, "Synthetic peer", connected, true, ByteArray(32) { 7 }, ByteArray(32) { 8 }, false, 1)
        override fun updatePeerInfo(peerID: String, nickname: String, noisePublicKey: ByteArray,
            signingPublicKey: ByteArray, isVerified: Boolean): Boolean { calls += "update-peer"; return true }
        override fun getIdentityFingerprint() = "synthetic-fingerprint"
        override fun getAuthenticatedRemoteStaticKey(peerID: String) = ByteArray(32) { 7 }
        override fun getStaticNoisePublicKey() = ByteArray(32) { 7 }
        override fun shouldShowEncryptionIcon(peerID: String) = connected
        override fun getEncryptedPeers() = if (connected) listOf(PEER) else emptyList()
        override fun getDeviceAddressForPeer(peerID: String): String? = null
        override fun getDeviceAddressToPeerMapping() = emptyMap<String, String>()
        override fun printDeviceAddressesForPeers() = ""
        override fun getDebugStatus() = "Synthetic transport"
        override fun clearAllInternalData() { calls += "erase-state" }
        override fun clearAllEncryptionData() { calls += "erase-crypto" }
    }

    private companion object {
        const val PEER = "1122334455667788"
        val KEY_HEX = "07".repeat(32)
        const val MESSAGE_ID = "synthetic-owner-message"
        fun message() = BitchatMessage(id = MESSAGE_ID, sender = "Synthetic peer", content = "Synthetic message",
            timestamp = Date(1), isPrivate = true, senderPeerID = PEER)
    }
}
