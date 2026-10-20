package com.bitchat.android.ui

import com.bitchat.android.mesh.*
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.service.*
import com.bitchat.android.sync.GossipSyncManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Real original processors and inert facade ports; no Bluetooth or platform graph. */
class PanicOriginalBluetoothProcessorsTest {
    @Test fun heldValidationRetainsCollectorUntilOriginalRootReturns() = runBlocking {
        val post = withFixture { f ->
            val held = f.holdValidation()
            f.initialProcessor.processPacket(packet())
            f.ready(held.entered)
            f.retireOriginalFacade()
            val call = f.launchCollector()
            check(f.initialProcessor.originalShutdownRequested(f.originalBinding))
            val originalAwait = f.launchOriginalAwait(f.initialProcessor)
            check(!originalAwait.isCompleted && !held.returned.get() && held.release.count == 1L)
            val completedBeforeRelease = call.job.isCompleted
            if (completedBeforeRelease) f.requireEligibleBaseline(checkNotNull(call.result.get()))
            return@withFixture {
                assertFalse("The collector must not return while the same original callback is held", completedBeforeRelease)
                assertEquals(listOf(f.originalBinding.identity), f.evidence(checkNotNull(call.result.get())).awaitReturnedRoots)
            }
        }
        post()
    }

    @Test fun heldCallbackChildRetainsCollectorUntilOriginalRootReturns() = runBlocking {
        val post = withFixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = f.deferredGate()
            val child = AtomicReference<Job>()
            val callbackReturned = AtomicBoolean(false)
            f.validation = {
                runBlocking {
                    f.own(checkNotNull(currentCoroutineContext()[Job]))
                    val actual = launch(start = CoroutineStart.UNDISPATCHED) {
                        withContext(NonCancellable) { entered.complete(Unit); f.waitDeferredGate(release) }
                    }
                    f.own(actual)
                    child.set(actual)
                    actual.join()
                }
                callbackReturned.set(true)
                false
            }
            f.initialProcessor.processPacket(packet())
            f.ready(entered)
            check(checkNotNull(child.get()).isActive && !callbackReturned.get())
            f.retireOriginalFacade()
            val call = f.launchCollector()
            check(f.initialProcessor.originalShutdownRequested(f.originalBinding))
            val originalAwait = f.launchOriginalAwait(f.initialProcessor)
            check(!originalAwait.isCompleted && !release.isCompleted && !callbackReturned.get())
            val completedBeforeRelease = call.job.isCompleted
            if (completedBeforeRelease) f.requireEligibleBaseline(checkNotNull(call.result.get()))
            return@withFixture {
                assertFalse("The original actor must remain owned until its actual callback child returns", completedBeforeRelease)
                assertEquals(listOf(f.originalBinding.identity), f.evidence(checkNotNull(call.result.get())).awaitReturnedRoots)
            }
        }
        post()
    }

    @Test fun allKnownRequestsPrecedeOriginalFacadeDetach() = runBlocking {
        val post = withFixture { f ->
            f.replaceBluetooth()
            f.transport.delegate = f.facade
            val observed = mutableListOf<Boolean>()
            f.transport.onDetach = { observed += f.processors.all { it.originalShutdownRequested(it.captureOriginalProcessorBinding()) } }
            f.fence()
            val report = f.collect()
            val evidence = f.evidence(report)
            return@withFixture {
                assertEquals(listOf(true), observed)
                assertEquals(2, evidence.requests.size)
                assertTrue(evidence.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
                f.assertUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun completedOriginalRootKeepsEveryAggregateUnconfirmed() = runBlocking {
        val post = withFixture { f ->
            f.retireOriginalFacade()
            val report = f.collect()
            val evidence = f.evidence(report)
            check(evidence.holderGaps.isEmpty() && evidence.callerGaps.isEmpty())
            return@withFixture {
                assertEquals(1, evidence.rows.size)
                assertSame(f.originalBinding.identity, evidence.rows.single().root)
                assertEquals(PanicProcessorRequestStatus.RequestReturned, evidence.requests.single().status)
                f.assertUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun replacedAndClearedProductsRetainBothOriginalRoots() = runBlocking {
        val post = withFixture { f ->
            f.replaceBluetooth()
            f.holder.clear()
            check(f.holder.meshService == null && f.holder.unifiedMeshService == null)
            f.retireOriginalFacade()
            val report = f.collect()
            val evidence = f.evidence(report)
            return@withFixture {
                assertEquals(2, evidence.rows.size)
                assertEquals(2, evidence.requests.size)
                assertNotSame(evidence.rows[0].root, evidence.rows[1].root)
                assertTrue(evidence.holderGaps.isEmpty())
                f.assertUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun unknownParentStillRequestsAllocatedRootWithoutEligibleBatch() = runBlocking {
        val post = withFixture { f ->
            val sentinel = IllegalStateException("synthetic parent constructor failure")
            f.holder.clear()
            var caught: Throwable? = null
            try { f.holder.getOrCreate { context -> f.createBluetooth(context); throw sentinel } }
            catch (failure: Throwable) { caught = failure }
            check(caught === sentinel && f.processors.size == 2)
            f.retireOriginalFacade()
            val report = f.collect()
            val evidence = f.evidence(report)
            return@withFixture {
                assertSame(sentinel, caught)
                assertEquals(2, evidence.requests.size)
                assertEquals(PanicProcessorBatchStatus.Ineligible, evidence.status)
                assertTrue(HolderProcessorGap.UnknownParent in evidence.holderGaps)
                f.assertUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun missingProcessorOnReturnedBluetoothRefusesWholeBatch() = runBlocking {
        val post = withFixture { f ->
            f.holder.clear()
            f.acquired(f.holder.getOrCreate { InertMesh() })
            f.retireOriginalFacade()
            val report = f.collect()
            val evidence = f.evidence(report)
            return@withFixture {
                assertEquals(PanicProcessorBatchStatus.Ineligible, evidence.status)
                assertTrue(HolderProcessorGap.MissingProcessor in evidence.holderGaps)
                assertEquals(1, evidence.requests.size)
                f.assertUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun foreignAndEndedCreatorContextsRefuseBeforeAllocation() = runBlocking {
        val post = withFixture { f ->
            val foreign = f.otherHolder()
            var allocations = 0
            var foreignRefusal: HolderContextOperationRefusal? = null
            try { foreign.constructBluetoothProcessor(f.initialContext) { allocations++; error("Unreachable creator") } }
            catch (refused: HolderContextOperationRefusal) { foreignRefusal = refused }
            f.fence()
            var endedRefusal: HolderContextOperationRefusal? = null
            try { f.holder.constructBluetoothProcessor(f.initialContext) { allocations++; error("Unreachable creator") } }
            catch (refused: HolderContextOperationRefusal) { endedRefusal = refused }
            return@withFixture {
                assertEquals(0, allocations)
                assertEquals(HolderContextOperationGap.ForeignContext, checkNotNull(foreignRefusal).gap)
                assertEquals(HolderContextOperationGap.EndedContextAfterClose, checkNotNull(endedRefusal).gap)
            }
        }
        post()
    }

    @Test fun lateCreatorRequiresExplicitRequestRetry() = runBlocking {
        val post = withFixture { f ->
            val held = f.holdValidation()
            f.initialProcessor.processPacket(packet())
            f.ready(held.entered)
            f.transport.reusable = false
            val entered = CountDownLatch(1)
            val release = f.gate()
            val late = AtomicReference<PacketProcessor>()
            val worker = f.worker {
                f.acquired(f.holder.getOrCreate { context ->
                    val processor = f.holder.constructBluetoothProcessor(context) {
                        f.newProcessor().also { late.set(it); entered.countDown(); f.waitGate(release) }
                    }
                    check(processor === late.get())
                    InertMesh()
                })
            }
            f.await(entered)
            f.retireOriginalFacade()
            val first = f.collect()
            val beforeRetry = checkNotNull(late.get()).originalShutdownRequested(checkNotNull(late.get()).captureOriginalProcessorBinding())
            val firstEvidence = f.evidence(first)
            check(!held.returned.get())
            release.countDown()
            f.reap(worker)
            held.release.countDown()
            f.initialProcessor.awaitShutdown()
            val retry = f.collect()
            val retryEvidence = f.evidence(retry)
            return@withFixture {
                assertFalse(beforeRetry)
                assertEquals(PanicProcessorBatchStatus.Ineligible, firstEvidence.status)
                assertTrue(HolderProcessorGap.ActiveEntry in firstEvidence.holderGaps)
                assertEquals(2, retryEvidence.requests.size)
                assertTrue(retryEvidence.holderGaps.isEmpty())
                f.assertUnconfirmed(first)
                f.assertUnconfirmed(retry)
            }
        }
        post()
    }

    @Test fun changedCallingJobCannotUsePreparedAwait() = runBlocking {
        val post = withFixture { f ->
            f.fence()
            check(requestOriginalProcessorShutdown(f.originalBinding))
            val caller = captureOriginalProcessorCaller()
            val same = prepareOriginalProcessorAwait(f.originalBinding, caller) as OriginalProcessorPreparation.Ready
            check(validateOriginalProcessorPreparation(same.preparation))
            val other = f.own(f.scope.async(start = CoroutineStart.UNDISPATCHED) {
                prepareOriginalProcessorAwait(f.originalBinding, caller) to validateOriginalProcessorPreparation(same.preparation)
            })
            val actual = withTimeout(GUARD_MS) { other.await() }
            return@withFixture {
                assertEquals(OriginalProcessorGap.ChangedCaller, (actual.first as OriginalProcessorPreparation.Unconfirmed).gap)
                assertFalse(actual.second)
            }
        }
        post()
    }

    @Test fun cancelledCallerRequestsNoOriginalRoot() = runBlocking {
        val post = withFixture { f ->
            f.fence()
            val reported = AtomicBoolean(false)
            val call = f.own(f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel(CancellationException("synthetic caller cancellation"))
                f.collect()
                reported.set(true)
            })
            f.join(call)
            val requested = f.initialProcessor.originalShutdownRequested(f.originalBinding)
            return@withFixture {
                assertTrue(call.isCancelled)
                assertFalse(reported.get())
                assertFalse(requested)
            }
        }
        post()
    }

    @Test fun genericMissingAndMismatchedInputsProduceNoProcessorEffects() = runBlocking {
        val post = withFixture { f ->
            f.fence()
            val generic = captureHolderPanicBinding(f.holder, f.facade)
            val missing = collectPanicRetirementWithOriginalBluetoothProcessors(f.facade, PanicOriginalGossipInput.Installed(generic, f.facade))
            val invalid = collectPanicRetirementWithOriginalBluetoothProcessors(f.facade, PanicOriginalGossipInput.Installed(f.binding, Any()))
            val requested = f.initialProcessor.originalShutdownRequested(f.originalBinding)
            return@withFixture {
                assertFalse(requested)
                assertEquals(listOf(HolderProcessorGap.MissingCollector), f.evidence(missing).holderGaps)
                assertEquals(PanicProcessorBatchStatus.InvalidInput, f.evidence(invalid).status)
                f.assertUnconfirmed(missing)
                f.assertUnconfirmed(invalid)
            }
        }
        post()
    }

    private class Call(val job: Job, val result: AtomicReference<PanicRetirementReport>)
    private class Held(val entered: CompletableDeferred<Unit>, val release: CountDownLatch, val returned: AtomicBoolean)

    private class Fixture {
        val processors = CopyOnWriteArrayList<PacketProcessor>()
        private val knownJobs = CopyOnWriteArrayList<Job>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val gates = CopyOnWriteArrayList<CountDownLatch>()
        private val deferredGates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        private val errors = CopyOnWriteArrayList<Throwable>()
        private val facadeJob = SupervisorJob()
        private val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default + CoroutineExceptionHandler { _, failure -> errors += failure })
        @Volatile var validation: (BitchatPacket) -> Boolean = { false }
        val holder = otherHolder()
        lateinit var transport: InertMesh
            private set
        lateinit var initialContext: HolderConstructionContext
            private set
        lateinit var facade: UnifiedMeshService
            private set
        lateinit var binding: HolderPanicBinding
            private set
        val initialProcessor: PacketProcessor get() = processors.first()
        val originalBinding: OriginalProcessorBinding get() = initialProcessor.captureOriginalProcessorBinding()

        fun initialize() {
            facade = acquired(holder.getUnifiedOrCreate({ context ->
                initialContext = context
                createBluetooth(context).also { transport = it }
            }) { bluetooth ->
                UnifiedMeshService(bluetooth, UnifiedMeshPorts(
                    scope = CoroutineScope(facadeJob + Dispatchers.Default), announcementProfile = MutableStateFlow(60_000L to false),
                    hasAnnouncementRecipients = { false }, currentWifi = { null }, startWifi = {}, stopWifi = {},
                    bleEnabled = { true }, disableBle = {}, currentNpub = { null }
                ))
            })
            binding = captureHolderPanicBindingWithGossip(holder, facade)
        }

        fun otherHolder() = MeshServiceHolderOwner(MeshServiceHolderPorts<InertMesh, UnifiedMeshService, GossipSyncManager>(
            isReusable = { it.reusable }, stopBluetooth = {}, refreshUnified = {}, startGossip = {}, stopGossip = {}, clearGossip = {},
            installGossipDelegate = { manager, delegate -> manager.delegate = delegate },
            broadcast = {}, sendToPeer = { _, _ -> }, log = { _, _ -> }
        ))
        fun <T : Any> acquired(result: HolderAcquisitionResult<T>): T = when (result) {
            is HolderAcquisitionResult.Available -> result.product
            HolderAcquisitionResult.RefusedBeforeAcquisition -> error("Expected an admitted inert fixture constructor")
        }
        fun createBluetooth(context: HolderConstructionContext): InertMesh {
            holder.constructBluetoothProcessor(context) { newProcessor() }
            return InertMesh()
        }
        fun replaceBluetooth() {
            transport.reusable = false
            acquired(holder.getOrCreate { createBluetooth(it) })
        }
        fun newProcessor() = PacketProcessor(MY_ID).also { processor ->
            processors += processor
            processor.delegate = object : PacketProcessorDelegate {
                override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) = validation(packet)
                override fun updatePeerLastSeen(peerID: String) = Unit
                override fun getPeerNickname(peerID: String): String? = null
                override fun getNetworkSize() = 1
                override fun getBroadcastRecipient() = SpecialRecipients.BROADCAST
                override fun handleNoiseHandshake(routed: RoutedPacket) = false
                override fun handleNoiseEncrypted(routed: RoutedPacket) = false
                override suspend fun handleAnnounce(routed: RoutedPacket) = false
                override fun handleMessage(routed: RoutedPacket) = Unit
                override fun handleLeave(routed: RoutedPacket) = Unit
                override fun handleFragment(packet: BitchatPacket, ingressKey: String) = FragmentResult.Pending
                override fun handleRequestSync(routed: RoutedPacket) = Unit
                override fun sendAnnouncementToPeer(peerID: String) = Unit
                override fun sendCachedMessages(peerID: String) = Unit
                override fun relayPacket(routed: RoutedPacket) = Unit
                override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
            }
        }
        fun fence() {
            check(PanicResetCoordinator.fenceCapturedPanicOwners(facade, binding, facade) === HolderConstructionCloseOutcome.ClosedForConstruction)
        }
        suspend fun retireOriginalFacade() {
            fence()
            check(facade.retireForPanic() && facadeJob.isCompleted)
        }
        suspend fun collect(): PanicRetirementReport {
            val report = collectPanicRetirementWithOriginalBluetoothProcessors(facade, PanicOriginalGossipInput.Installed(binding, facade))
            check(report.facade === PanicOwnerRetirement.Confirmed) { "The actual inert facade must retire successfully" }
            return report
        }
        fun evidence(report: PanicRetirementReport): PanicOriginalProcessorEvidence =
            ((report.transportCoreRadioAndHandlers as PanicOwnerRetirement.Unconfirmed).evidence as PanicUnconfirmedEvidence.OriginalBluetoothProcessors).observation
        fun assertUnconfirmed(report: PanicRetirementReport) {
            assertFalse(report.confirmed)
            assertTrue(report.wifiController is PanicOwnerRetirement.Unconfirmed)
            assertTrue(report.holderConstructionAndCallbacks is PanicOwnerRetirement.Unconfirmed)
            assertTrue(report.gossipJobsAndWriters is PanicOwnerRetirement.Unconfirmed)
            assertTrue(report.transportCoreRadioAndHandlers is PanicOwnerRetirement.Unconfirmed)
            assertTrue(report.startupAndRemainingSharedKeyUsers is PanicOwnerRetirement.Unconfirmed)
        }
        fun requireEligibleBaseline(report: PanicRetirementReport) {
            val e = evidence(report)
            check(report.facade === PanicOwnerRetirement.Confirmed && facadeJob.isCompleted)
            check(e.status == PanicProcessorBatchStatus.RequestedNotAwaited && e.holderGaps.isEmpty() && e.callerGaps.isEmpty())
            check(e.requests.size == 1 && e.requests.single().root === originalBinding.identity &&
                e.requests.single().status == PanicProcessorRequestStatus.RequestReturned)
            check(e.rows.size == 1 && e.rows.single().root === originalBinding.identity && e.rows.single().state == HolderProcessorState.Returned)
            healthy()
        }
        fun <T : Job> own(job: T): T = job.also { if (knownJobs.none { original -> original === it }) knownJobs += it }
        fun launchCollector(): Call {
            val result = AtomicReference<PanicRetirementReport>()
            val job = own(scope.launch(start = CoroutineStart.UNDISPATCHED) { result.set(collect()) })
            return Call(job, result)
        }
        fun launchOriginalAwait(processor: PacketProcessor): Job = own(scope.launch(start = CoroutineStart.UNDISPATCHED) { processor.awaitShutdown() })
        fun gate() = CountDownLatch(1).also { gates += it }
        fun deferredGate() = CompletableDeferred<Unit>().also { deferredGates += it }
        fun holdValidation(): Held {
            val entered = CompletableDeferred<Unit>()
            val release = gate()
            val returned = AtomicBoolean(false)
            validation = { entered.complete(Unit); waitGate(release); returned.set(true); false }
            return Held(entered, release, returned)
        }
        fun waitGate(gate: CountDownLatch) {
            try {
                if (gate.await(GUARD_MS, TimeUnit.MILLISECONDS)) return
                errors += AssertionError("Owned callback or constructor exceeded its readiness guard")
            } catch (failure: InterruptedException) { errors += failure }
            while (gate.count != 0L) {
                try { gate.await() } catch (failure: InterruptedException) { errors += failure }
            }
        }
        suspend fun waitDeferredGate(gate: CompletableDeferred<Unit>) {
            try { withTimeout(GUARD_MS) { gate.await() } }
            catch (failure: TimeoutCancellationException) {
                errors += AssertionError("Owned callback child exceeded its readiness guard", failure)
                gate.await()
            }
        }
        suspend fun ready(signal: CompletableDeferred<Unit>) { withTimeout(GUARD_MS) { signal.await() } }
        fun await(signal: CountDownLatch) { check(signal.await(GUARD_MS, TimeUnit.MILLISECONDS)) { "Actual creator did not enter" } }
        suspend fun join(job: Job) { withTimeout(GUARD_MS) { job.join() } }
        fun worker(action: () -> Unit): Thread = Thread {
            try { action() } catch (failure: Throwable) { errors += failure }
        }.apply { isDaemon = true; workers += this; start() }
        fun reap(worker: Thread) { worker.join(GUARD_MS); check(!worker.isAlive); healthy() }
        fun healthy() {
            val actual = errors.toList()
            if (actual.isNotEmpty()) {
                val first = AssertionError("Unexpected owned fixture work failure", actual.first())
                actual.drop(1).forEach { if (it !== first && it !== first.cause) first.addSuppressed(it) }
                throw first
            }
        }
        suspend fun close() = withContext(NonCancellable) {
            val failures = mutableListOf<Throwable>()
            suspend fun attempt(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) { failures += failure }
            }
            suspend fun reapJob(job: Job) {
                attempt { withTimeout(GUARD_MS) { job.join() } }
                attempt { job.join() }
            }
            gates.forEach { it.countDown() }
            deferredGates.forEach { it.complete(Unit) }
            workers.forEach { worker ->
                attempt { worker.join(GUARD_MS); check(!worker.isAlive) { "Owned constructor exceeded its first reap guard" } }
                while (worker.isAlive) attempt { worker.join() }
            }
            processors.forEach { processor -> attempt { processor.shutdown() } }
            processors.forEach { processor ->
                attempt { withTimeout(GUARD_MS) { processor.awaitShutdown() } }
                attempt { processor.awaitShutdown() }
            }
            // Finite owned collectors must return after the real roots, before their parent is canceled.
            knownJobs.toList().forEach { job -> reapJob(job) }
            attempt { scopeJob.cancel() }
            if (::facade.isInitialized) attempt { check(facade.retireForPanic()) { "Owned inert facade did not retire" } }
            attempt { facadeJob.cancel() }
            reapJob(facadeJob)
            reapJob(scopeJob)
            attempt { healthy() }
            val first = failures.firstOrNull()
            if (first != null) {
                failures.drop(1).forEach { if (it !== first) first.addSuppressed(it) }
                throw first
            }
        }
    }
    private class InertMesh(var reusable: Boolean = true) : MeshService {
        var erasures = 0
        override val myPeerID = "1122334455667788"
        var onDetach: (() -> Unit)? = null
        private var installedDelegate: MeshDelegate? = null
        override var delegate: MeshDelegate?
            get() = installedDelegate
            set(value) {
                val previous = installedDelegate
                installedDelegate = value
                if (previous != null && value == null) onDetach?.invoke()
            }
        override fun startServices() {}
        override fun stopServices() {}
        override fun sendMessage(content: String, mentions: List<String>) {}
        override fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String?) {}
        override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {}
        override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {}
        override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {}
        override fun sendBroadcastAnnounce() {}
        override fun sendAnnouncementToPeer(peerID: String) {}
        override fun getPeerNicknames() = emptyMap<String, String>()
        override fun getPeerRSSI() = emptyMap<String, Int>()
        override fun getActivePeerCount() = 0
        override fun hasEstablishedSession(peerID: String) = false
        override fun getSessionState(peerID: String) = NoiseSession.NoiseSessionState.Uninitialized
        override fun initiateNoiseHandshake(peerID: String) {}
        override fun getPeerFingerprint(peerID: String): String? = null
        override fun getPeerInfo(peerID: String): PeerInfo? = null
        override fun updatePeerInfo(peerID: String, nickname: String, noisePublicKey: ByteArray, signingPublicKey: ByteArray, isVerified: Boolean) = false
        override fun getIdentityFingerprint() = "synthetic-fingerprint"
        override fun getStaticNoisePublicKey(): ByteArray? = null
        override fun shouldShowEncryptionIcon(peerID: String) = false
        override fun getEncryptedPeers() = emptyList<String>()
        override fun getDeviceAddressForPeer(peerID: String): String? = null
        override fun getDeviceAddressToPeerMapping() = emptyMap<String, String>()
        override fun printDeviceAddressesForPeers() = ""
        override fun getDebugStatus() = "Synthetic transport"
        override fun clearAllInternalData() { erasures++ }
        override fun clearAllEncryptionData() { erasures++ }
    }

    companion object {
        private const val GUARD_MS = 5_000L
        private const val MY_ID = "1111111111111111"
        private const val PEER_ID = "2222222222222222"
        private fun packet() = RoutedPacket(BitchatPacket(
            version = 1u, type = MessageType.MESSAGE.value,
            senderID = PEER_ID.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            recipientID = SpecialRecipients.BROADCAST, timestamp = 1uL,
            payload = byteArrayOf(1), ttl = 0u
        ), PEER_ID, ingressLinkID = "synthetic-link")
        private suspend fun withFixture(block: suspend (Fixture) -> (() -> Unit)): () -> Unit {
            val fixture = Fixture()
            var primary: Throwable? = null
            var post: (() -> Unit)? = null
            try {
                fixture.initialize()
                post = block(fixture)
                fixture.healthy()
            } catch (failure: Throwable) { primary = failure }
            finally {
                try { fixture.close() } catch (cleanup: Throwable) {
                    val original = primary
                    if (original == null) primary = cleanup else if (cleanup !== original) original.addSuppressed(cleanup)
                }
            }
            primary?.let { throw it }
            return checkNotNull(post)
        }
    }
}
