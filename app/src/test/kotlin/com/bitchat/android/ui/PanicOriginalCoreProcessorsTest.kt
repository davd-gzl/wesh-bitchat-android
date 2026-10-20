package com.bitchat.android.ui

import android.content.Context
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.mesh.*
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.service.*
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.wifiaware.WifiAwareFactoryResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Actual original Core/processor and owner; all six eager platform dependencies are intercepted. */
class PanicOriginalCoreProcessorsTest {
    @Test fun heldOriginalCoreValidationKeepsUnionConsumerPending() = runBlocking {
        val post = withFixture { f ->
            val core = f.newCore()
            val held = f.hold(core)
            core.core().processIncoming(packet(), PEER_ID, null, "synthetic-link")
            f.ready(held.entered)
            f.retire()
            val call = f.launchCollector()
            val processor = core.processor()
            check(processor.originalShutdownRequested(core.binding()))
            val independent = f.launchOriginalAwait(processor)
            check(!independent.isCompleted && !held.returned.get() && held.release.count == 1L)
            val completedBeforeRelease = call.job.isCompleted
            if (completedBeforeRelease) f.requireRequestedOnly(checkNotNull(call.result.get()), core)
            f.healthy()
            return@withFixture {
                assertFalse("The union consumer must await the actual held Core actor", completedBeforeRelease)
                assertTrue(f.evidence(checkNotNull(call.result.get())).awaitReturnedRoots.any { it === core.binding().identity })
            }
        }
        post()
    }

    @Test fun heldOriginalCoreCallbackChildKeepsUnionConsumerPending() = runBlocking {
        val post = withFixture { f ->
            val core = f.newCore()
            val entered = CompletableDeferred<Unit>()
            val release = f.deferredGate()
            val published = CompletableDeferred<Unit>()
            val child = AtomicReference<Job>()
            val callbackReturned = AtomicBoolean(false)
            core.validation = {
                runBlocking {
                    f.own(checkNotNull(currentCoroutineContext()[Job]))
                    val actual = launch(start = CoroutineStart.UNDISPATCHED) {
                        withContext(NonCancellable) { entered.complete(Unit); f.waitDeferredGate(release) }
                    }
                    f.own(actual)
                    child.set(actual)
                    published.complete(Unit)
                    actual.join()
                }
                callbackReturned.set(true)
                false
            }
            core.core().processIncoming(packet(), PEER_ID, null, "synthetic-link")
            f.ready(entered)
            f.ready(published)
            check(checkNotNull(child.get()).isActive && !callbackReturned.get())
            f.retire()
            val call = f.launchCollector()
            check(core.processor().originalShutdownRequested(core.binding()))
            val independent = f.launchOriginalAwait(core.processor())
            check(!independent.isCompleted && !release.isCompleted && !callbackReturned.get())
            val completedBeforeRelease = call.job.isCompleted
            if (completedBeforeRelease) f.requireRequestedOnly(checkNotNull(call.result.get()), core)
            f.healthy()
            return@withFixture {
                assertFalse("The actual Core callback must return after its owned child", completedBeforeRelease)
                assertTrue(f.evidence(checkNotNull(call.result.get())).awaitReturnedRoots.any { it === core.binding().identity })
            }
        }
        post()
    }

    @Test fun allBluetoothAndCoreRequestsPrecedeFacadeAndAnyJoin() = runBlocking {
        val post = withFixture { f ->
            f.newCore()
            val atDetach = mutableListOf<List<Boolean>>()
            f.transport.delegate = f.facade
            f.transport.onDetach = { atDetach += f.processors.map { it.originalShutdownRequested(it.captureOriginalProcessorBinding()) } }
            f.fence()
            val report = f.collect()
            val evidence = f.evidence(report)
            val requested = f.processors.map { it.originalShutdownRequested(it.captureOriginalProcessorBinding()) }
            val oldReport = collectPanicRetirementWithOriginalBluetoothProcessors(f.facade, PanicOriginalGossipInput.Installed(f.binding, f.facade))
            val oldEvidence = ((oldReport.transportCoreRadioAndHandlers as PanicOwnerRetirement.Unconfirmed).evidence as
                PanicUnconfirmedEvidence.OriginalBluetoothProcessors).observation
            return@withFixture {
                assertEquals(listOf(listOf(true, true)), atDetach)
                assertEquals(listOf(true, true), requested)
                assertEquals(listOf(HolderProcessorSource.BluetoothService, HolderProcessorSource.WifiCore), evidence.rows.map { it.source })
                assertTrue(evidence.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
                f.assertTruthfulCompletePass(evidence)
                assertEquals(listOf(HolderProcessorSource.BluetoothService), oldEvidence.rows.map { it.source })
                assertEquals(listOf(f.bluetoothBinding.identity), oldEvidence.requests.map { it.root })
                assertEquals(PanicProcessorBatchStatus.AwaitReturned, oldEvidence.status)
                assertEquals(listOf(f.bluetoothBinding.identity), oldEvidence.awaitReturnedRoots)
                f.assertUnconfirmed(oldReport)
                f.assertUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun latePreacceptedCoreRequiresWholeUnionRequestRetry() = runBlocking {
        val post = withFixture { f ->
            val held = f.holdBluetooth()
            f.bluetoothProcessor.processPacket(RoutedPacket(packet(), PEER_ID, ingressLinkID = "synthetic-link"))
            f.ready(held.entered)
            val beginCore = f.gate()
            val accepted = CountDownLatch(1)
            val late = CoreRecord()
            val worker = f.coreWorker(late, beforeCore = { accepted.countDown(); f.waitGate(beginCore) })
            f.await(accepted)
            f.transport.delegate = f.facade
            f.transport.onDetach = { beginCore.countDown(); f.reap(worker) }
            f.fence()
            val first = f.launchCollector()
            val completedFirst = first.job.isCompleted
            val report1 = first.result.get()
            val e1 = report1?.let { f.evidence(it) }
            val beforeRelease = !held.returned.get() && held.release.count == 1L
            val requestedA = f.bluetoothProcessor.originalShutdownRequested(f.bluetoothBinding)
            val requestedB1 = late.processor().originalShutdownRequested(late.binding())
            held.release.countDown()
            f.awaitProcessor(f.bluetoothProcessor)
            f.join(first.job)
            val report2 = f.collect()
            val e2 = f.evidence(report2)
            val requestedB2 = late.processor().originalShutdownRequested(late.binding())
            return@withFixture {
                assertTrue(completedFirst && beforeRelease && requestedA)
                assertFalse(requestedB1)
                assertEquals(PanicProcessorBatchStatus.NeedsExplicitRetry, checkNotNull(e1).status)
                assertTrue(e1.awaitReturnedRoots.isEmpty())
                assertTrue(requestedB2)
                assertEquals(2, e2.requests.size)
                assertTrue(e2.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
                f.assertTruthfulCompletePass(e2)
                f.assertUnconfirmed(checkNotNull(report1))
                f.assertUnconfirmed(report2)
            }
        }
        post()
    }

    @Test fun allocatedCoreSetterThrowRetainsKnownChildAndUnknownParent() = runBlocking {
        val post = withFixture { f ->
            val sentinel = IllegalStateException("synthetic Core setter failure")
            val core = f.newCore(setterFailure = sentinel)
            check(core.failure.get() === sentinel && core.setterMatchesOriginal())
            f.retire()
            val report = f.collect()
            val evidence = f.evidence(report)
            val requested = core.processor().originalShutdownRequested(core.binding())
            return@withFixture {
                assertSame(sentinel, core.failure.get())
                assertTrue(requested)
                assertEquals(PanicProcessorBatchStatus.Ineligible, evidence.status)
                assertTrue(HolderProcessorGap.UnknownParent in evidence.holderGaps)
                assertEquals(HolderProcessorState.Returned, evidence.rows.single { it.source == HolderProcessorSource.WifiCore }.state)
                assertTrue(evidence.awaitReturnedRoots.isEmpty())
                f.assertUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun foreignOrWrongKindCoreContextRunsNoCreator() = runBlocking {
        val post1 = withFixture { f ->
            f.retire()
            val ble = f.captureBluetooth()
            val union = f.captureUnion()
            val bleAsUnion = validateHolderOriginalBluetoothAndCoreProcessors(f.binding, f.facade, ble)
            val unionAsBle = validateHolderOriginalBluetoothProcessors(f.binding, f.facade, union)
            val correctBle = validateHolderOriginalBluetoothProcessors(f.binding, f.facade, ble)
            val correctUnion = validateHolderOriginalBluetoothAndCoreProcessors(f.binding, f.facade, union)
            val other = f.otherHolder()
            lateinit var foreign: HolderWifiConstructionContext
            val foreignResult = other.constructWifi(other.initialProcessPreparation().origin, Any()) { foreign = it; Unit }
            check(foreignResult is WifiAwareFactoryResult.Created)
            var creators = 0
            fun denied(context: HolderConstructionContext): HolderContextOperationRefusal {
                try { f.holder.constructWifiProcessor(context) { creators++; error("Refused creator must not run") } }
                catch (failure: HolderContextOperationRefusal) { return failure }
                error("Actual context validation must refuse")
            }
            val foreignRefusal = denied(foreign)
            val wrongKind = denied(f.bluetoothContext)
            return@withFixture {
                assertTrue(correctBle && correctUnion)
                assertFalse(bleAsUnion || unionAsBle)
                assertEquals(0, creators)
                assertEquals(HolderContextOperationGap.ForeignContext, foreignRefusal.gap)
                assertEquals(HolderContextOperationGap.WrongContextKind, wrongKind.gap)
            }
        }
        val post2 = withFixture { f ->
            val core = f.newCore()
            f.retire()
            var creators = 0
            var refused: HolderContextOperationRefusal? = null
            try { f.holder.constructWifiProcessor(checkNotNull(core.context.get())) { creators++; error("Ended creator must not run") } }
            catch (failure: HolderContextOperationRefusal) { refused = failure }
            check(refused != null)
            return@withFixture {
                assertEquals(0, creators)
                assertEquals(HolderContextOperationGap.EndedContextAfterClose, checkNotNull(refused).gap)
            }
        }
        post1()
        post2()
    }

    @Test fun cancelledOrChangedCallerNeverPromotesUnionAwait() = runBlocking {
        val post = withFixture { f ->
            val core = f.newCore()
            f.retire()
            val sentinel = CancellationException("synthetic collector cancellation")
            val cancelled = AtomicReference<Throwable>()
            val forbiddenResult = AtomicReference<PanicRetirementReport>()
            val caller = f.own(f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                checkNotNull(currentCoroutineContext()[Job]).cancel(sentinel)
                try { forbiddenResult.set(f.collect()) } catch (failure: CancellationException) { cancelled.set(failure) }
            })
            f.join(caller)
            val crossResult = AtomicReference<Boolean>()
            val changed = f.own(f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val capturedCaller = captureOriginalProcessorCaller()
                requestOriginalProcessorShutdown(core.binding())
                val prepared = prepareOriginalProcessorAwait(core.binding(), capturedCaller)
                check(prepared is OriginalProcessorPreparation.Ready)
                val otherJob = f.own(f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    crossResult.set(awaitOriginalProcessor(prepared.preparation))
                })
                otherJob.join()
            })
            f.join(changed)
            val actualCancellation = cancelled.get()
            val positiveReturn = forbiddenResult.get()
            val actualCrossResult = crossResult.get()
            return@withFixture {
                assertSame(sentinel, actualCancellation)
                assertNull(positiveReturn)
                assertEquals(false, actualCrossResult)
            }
        }
        post()
    }

    @Test fun retainedReplacementRowsPreserveOriginalSourcesAndUnconfirmedOwners() = runBlocking {
        val post = withFixture { f ->
            f.newCore()
            f.replaceBluetooth()
            f.newCore()
            f.holder.clear()
            f.retire()
            val report = f.collect()
            val evidence = f.evidence(report)
            val requested = f.processors.map { it.originalShutdownRequested(it.captureOriginalProcessorBinding()) }
            return@withFixture {
                assertEquals(4, evidence.rows.size)
                assertEquals(2, evidence.rows.count { it.source == HolderProcessorSource.BluetoothService })
                assertEquals(2, evidence.rows.count { it.source == HolderProcessorSource.WifiCore })
                assertEquals(listOf(true, true, true, true), requested)
                assertEquals(4, evidence.requests.size)
                assertTrue(evidence.holderGaps.isEmpty() && evidence.callerGaps.isEmpty())
                assertEquals(4, evidence.rows.map { it.child }.distinct().size)
                f.assertTruthfulCompletePass(evidence)
                f.assertUnconfirmed(report)
            }
        }
        post()
    }

    private class Call(val job: Job, val result: AtomicReference<PanicRetirementReport>)
    private class Held(val entered: CompletableDeferred<Unit>, val release: CountDownLatch, val returned: AtomicBoolean)
    private class CoreRecord {
        val context = AtomicReference<HolderWifiConstructionContext>()
        val failure = AtomicReference<Throwable>()
        private val actualCore = AtomicReference<MeshCore>()
        private val actualProcessor = AtomicReference<PacketProcessor>()
        private val actualSetterProcessor = AtomicReference<PacketProcessor>()
        @Volatile var validation: (BitchatPacket) -> Boolean = { false }
        fun core(): MeshCore = checkNotNull(actualCore.get())
        fun installCore(core: MeshCore) { check(actualCore.compareAndSet(null, core)) }
        fun processorOrNull(): PacketProcessor? = actualProcessor.get()
        fun processor(): PacketProcessor = checkNotNull(processorOrNull())
        fun installProcessor(processor: PacketProcessor) { check(actualProcessor.compareAndSet(null, processor)) }
        fun captureSetter(processor: PacketProcessor) { check(actualSetterProcessor.compareAndSet(null, processor)) }
        fun setterMatchesOriginal(): Boolean = actualSetterProcessor.get() === processorOrNull() && processorOrNull() != null
        fun binding(): OriginalProcessorBinding = processor().captureOriginalProcessorBinding()
    }

    private class Fixture {
        val processors = CopyOnWriteArrayList<PacketProcessor>()
        private val knownJobs = CopyOnWriteArrayList<Job>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val gates = CopyOnWriteArrayList<CountDownLatch>()
        private val deferredGates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        private val errors = CopyOnWriteArrayList<Throwable>()
        private val facadeJob = SupervisorJob()
        private val scopeJob = SupervisorJob()
        private val coreHostJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default + CoroutineExceptionHandler { _, failure -> errors += failure })
        private val coreScope = CoroutineScope(coreHostJob + Dispatchers.Default + CoroutineExceptionHandler { _, failure -> errors += failure })
        val holder = otherHolder()
        private val original = holder.initialProcessPreparation().origin
        lateinit var facade: UnifiedMeshService
            private set
        lateinit var binding: HolderPanicBinding
            private set
        lateinit var transport: InertMesh
            private set
        lateinit var bluetoothContext: HolderConstructionContext
            private set
        val bluetoothProcessor: PacketProcessor get() = processors.first()
        val bluetoothBinding: OriginalProcessorBinding get() = bluetoothProcessor.captureOriginalProcessorBinding()

        fun initialize() {
            facade = acquired(holder.getUnifiedOrCreate({ context ->
                bluetoothContext = context
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
            HolderAcquisitionResult.RefusedBeforeAcquisition -> error("Expected actual fixture admission")
        }
        fun createBluetooth(context: HolderConstructionContext): InertMesh {
            val processor = holder.constructBluetoothProcessor(context) { PacketProcessor(MY_ID) }
            processors += processor
            processor.delegate = object : PacketProcessorDelegate {
                override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) = bluetoothValidation(packet)
                override fun updatePeerLastSeen(peerID: String) = Unit
                override fun getPeerNickname(peerID: String): String? = null
                override fun getNetworkSize() = 1
                override fun getBroadcastRecipient() = com.bitchat.android.protocol.SpecialRecipients.BROADCAST
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
            return InertMesh()
        }
        @Volatile private var bluetoothValidation: (BitchatPacket) -> Boolean = { false }
        fun replaceBluetooth() {
            transport.reusable = false
            acquired(holder.getOrCreate { createBluetooth(it) })
        }
        fun newCore(setterFailure: Throwable? = null): CoreRecord = CoreRecord().also {
            reap(coreWorker(it, setterFailure = setterFailure))
            if (setterFailure == null) check(it.failure.get() == null && it.setterMatchesOriginal())
        }
        fun coreWorker(
            record: CoreRecord, beforeCore: () -> Unit = {}, setterFailure: Throwable? = null
        ): Thread = worker {
            try {
                val result = holder.constructWifi(original, Any()) { context ->
                    check(record.context.compareAndSet(null, context))
                    beforeCore()
                    constructCoreHere(record, context, setterFailure)
                }
                check(result is WifiAwareFactoryResult.Created && result.value === record.core())
            } catch (failure: Throwable) {
                if (setterFailure != null && failure === setterFailure) record.failure.set(failure) else throw failure
            }
        }
        private fun constructCoreHere(record: CoreRecord, context: HolderWifiConstructionContext, setterFailure: Throwable?): MeshCore {
            val installationThread = Thread.currentThread()
            val controllers = mutableListOf<MockedConstruction<*>>()
            var primary: Throwable? = null
            var result: MeshCore? = null
            try {
                controllers += Mockito.mockConstruction(PeerManager::class.java)
                controllers += Mockito.mockConstruction(FragmentManager::class.java)
                controllers += Mockito.mockConstruction(SecureAuthenticatedPeerStateStore::class.java)
                controllers += Mockito.mockConstruction(SecurityManager::class.java) { instance, _ ->
                    doAnswer { call -> record.validation(call.getArgument(0)) }.whenever(instance).validatePacket(any(), any())
                }
                controllers += Mockito.mockConstruction(StoreForwardManager::class.java)
                controllers += Mockito.mockConstruction(MessageHandler::class.java) { instance, _ ->
                    doAnswer { call ->
                        val actual = call.getArgument<PacketProcessor>(0)
                        record.captureSetter(actual)
                        check(actual === record.processor())
                        if (setterFailure != null) throw setterFailure
                        null
                    }.whenever(instance).packetProcessor = any()
                }
                check(controllers.size == 6 && Thread.currentThread() === installationThread)
                val platform = mock<Context>()
                whenever(platform.applicationContext).thenReturn(platform)
                val actualCore = MeshCore(
                    context = platform, scope = coreScope, transport = mock<MeshTransport>(),
                    encryptionService = mock<EncryptionService>(), myPeerID = MY_ID, maxTtl = 0u,
                    sharedGossipManager = mock<GossipSyncManager>(),
                    gossipConfigProvider = object : GossipSyncManager.ConfigProvider {
                        override fun seenCapacity() = 8
                        override fun gcsMaxBytes() = 64
                        override fun gcsTargetFpr() = 0.01
                    },
                    recordFallbackGossip = { _ -> error("Shared inert manager must prevent fallback") },
                    recordPacketProcessor = { creator ->
                        holder.constructWifiProcessor(context, creator).also { record.installProcessor(it); processors += it }
                    },
                    hooks = MeshCore.Hooks(), weshLocalOnly = true
                )
                check(controllers.all { it.constructed().size == 1 })
                result = actualCore
                record.installCore(actualCore)
            } catch (failure: Throwable) { primary = failure }
            finally {
                if (record.processorOrNull() != null) {
                    try { check(controllers.size == 6 && controllers.all { it.constructed().size == 1 }) }
                    catch (failure: Throwable) { errors += failure }
                }
                controllers.asReversed().forEach { controller ->
                    try { check(Thread.currentThread() === installationThread); controller.close() }
                    catch (failure: Throwable) {
                        errors += failure
                        val originalFailure = primary
                        if (originalFailure == null) primary = failure else if (failure !== originalFailure) originalFailure.addSuppressed(failure)
                    }
                }
            }
            primary?.let { throw it }
            return checkNotNull(result)
        }
        fun hold(record: CoreRecord): Held = Held(CompletableDeferred(), gate(), AtomicBoolean(false)).also { held ->
            record.validation = { held.entered.complete(Unit); waitGate(held.release); held.returned.set(true); false }
        }
        fun holdBluetooth(): Held = Held(CompletableDeferred(), gate(), AtomicBoolean(false)).also { held ->
            bluetoothValidation = { held.entered.complete(Unit); waitGate(held.release); held.returned.set(true); false }
        }
        fun fence() { check(PanicResetCoordinator.fenceCapturedPanicOwners(facade, binding, facade) === HolderConstructionCloseOutcome.ClosedForConstruction) }
        suspend fun retire() {
            fence()
            check(facade.retireForPanic() && facadeJob.isCompleted)
            bluetoothProcessor.shutdown()
            awaitProcessor(bluetoothProcessor)
        }
        suspend fun collect(): PanicRetirementReport = collectPanicRetirementWithOriginalBluetoothAndCoreProcessors(
            facade, PanicOriginalGossipInput.Installed(binding, facade))
        fun launchCollector(): Call {
            val result = AtomicReference<PanicRetirementReport>()
            val job = own(scope.launch(start = CoroutineStart.UNDISPATCHED) { result.set(collect()) })
            return Call(job, result)
        }
        fun launchOriginalAwait(processor: PacketProcessor): Job = own(scope.launch(start = CoroutineStart.UNDISPATCHED) { processor.awaitShutdown() })
        fun captureBluetooth(): HolderProcessorBatch = (captureHolderOriginalBluetoothProcessors(binding, facade) as HolderProcessorCapture.Observed).batch
        fun captureUnion(): HolderProcessorBatch = (captureHolderOriginalBluetoothAndCoreProcessors(binding, facade) as HolderProcessorCapture.Observed).batch
        fun evidence(report: PanicRetirementReport): PanicOriginalProcessorEvidence =
            ((report.transportCoreRadioAndHandlers as PanicOwnerRetirement.Unconfirmed).evidence as PanicUnconfirmedEvidence.OriginalBluetoothAndCoreProcessors).observation
        fun requireRequestedOnly(report: PanicRetirementReport, core: CoreRecord) {
            val e = evidence(report)
            check(report.facade === PanicOwnerRetirement.Confirmed && facadeJob.isCompleted)
            check(e.status == PanicProcessorBatchStatus.RequestedNotAwaited && e.holderGaps.isEmpty() && e.callerGaps.isEmpty())
            check(e.rows.size == 2 && e.rows.single { it.source == HolderProcessorSource.WifiCore }.root === core.binding().identity)
            check(e.requests.size == 2 && e.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
            check(e.awaitReturnedRoots.size == 1 && e.awaitReturnedRoots.single() === bluetoothBinding.identity)
            check(e.awaitReturnedRoots.none { it === core.binding().identity })
        }
        fun assertTruthfulCompletePass(e: PanicOriginalProcessorEvidence) {
            assertTrue(e.holderGaps.isEmpty() && e.callerGaps.isEmpty())
            val ble = e.rows.filter { it.source == HolderProcessorSource.BluetoothService }.map { checkNotNull(it.root) }
            val core = e.rows.filter { it.source == HolderProcessorSource.WifiCore }.map { checkNotNull(it.root) }
            when (e.status) {
                PanicProcessorBatchStatus.RequestedNotAwaited -> {
                    assertEquals(ble, e.awaitReturnedRoots)
                    assertTrue(core.none { root -> e.awaitReturnedRoots.any { it === root } })
                }
                PanicProcessorBatchStatus.AwaitReturned -> assertEquals(e.rows.map { it.root }, e.awaitReturnedRoots)
                else -> fail("The complete pass must report truthful actual source-specific returns")
            }
        }
        fun assertUnconfirmed(report: PanicRetirementReport) {
            assertFalse(report.confirmed)
            assertTrue(report.wifiController is PanicOwnerRetirement.Unconfirmed)
            assertTrue(report.holderConstructionAndCallbacks is PanicOwnerRetirement.Unconfirmed)
            assertTrue(report.gossipJobsAndWriters is PanicOwnerRetirement.Unconfirmed)
            assertTrue(report.transportCoreRadioAndHandlers is PanicOwnerRetirement.Unconfirmed)
            assertTrue(report.startupAndRemainingSharedKeyUsers is PanicOwnerRetirement.Unconfirmed)
        }
        fun <T : Job> own(job: T): T = job.also { if (knownJobs.none { original -> original === it }) knownJobs += it }
        fun gate() = CountDownLatch(1).also { gates += it }
        fun deferredGate() = CompletableDeferred<Unit>().also { deferredGates += it }
        fun waitGate(gate: CountDownLatch) {
            try { if (gate.await(GUARD_MS, TimeUnit.MILLISECONDS)) return }
            catch (failure: InterruptedException) { errors += failure }
            errors += AssertionError("Owned callback or constructor exceeded its first readiness guard")
            while (gate.count != 0L) {
                try { gate.await() } catch (failure: InterruptedException) { errors += failure }
            }
        }
        suspend fun waitDeferredGate(gate: CompletableDeferred<Unit>) {
            try { withTimeout(GUARD_MS) { gate.await() } }
            catch (failure: TimeoutCancellationException) { errors += failure; gate.await() }
        }
        suspend fun ready(signal: CompletableDeferred<Unit>) { withTimeout(GUARD_MS) { signal.await() } }
        fun await(signal: CountDownLatch) { check(signal.await(GUARD_MS, TimeUnit.MILLISECONDS)) { "Actual constructor did not enter" } }
        suspend fun join(job: Job) { withTimeout(GUARD_MS) { job.join() } }
        suspend fun awaitProcessor(processor: PacketProcessor) { withTimeout(GUARD_MS) { processor.awaitShutdown() } }
        fun worker(action: () -> Unit): Thread = Thread {
            try { action() } catch (failure: Throwable) { errors += failure }
        }.apply { isDaemon = true; workers += this; start() }
        fun reap(worker: Thread) { worker.join(GUARD_MS); check(!worker.isAlive) { "Owned constructor exceeded its first reap guard" }; healthy() }
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
            suspend fun attempt(action: suspend () -> Unit) { try { action() } catch (failure: Throwable) { failures += failure } }
            suspend fun reapJob(job: Job) {
                attempt { withTimeout(GUARD_MS) { job.join() } }
                attempt { job.join() }
            }
            gates.forEach { it.countDown() }
            deferredGates.forEach { it.complete(Unit) }
            workers.forEach { worker ->
                attempt { worker.join(GUARD_MS); check(!worker.isAlive) { "Owned constructor exceeded its first reap guard" } }
                if (worker.isAlive) attempt { worker.interrupt() }
                while (worker.isAlive) attempt { worker.join() }
            }
            processors.forEach { processor -> attempt { processor.shutdown() } }
            processors.forEach { processor ->
                attempt { withTimeout(GUARD_MS) { processor.awaitShutdown() } }
                attempt { processor.awaitShutdown() }
            }
            knownJobs.toList().forEach { job -> reapJob(job) }
            attempt { scopeJob.cancel() }
            attempt { coreHostJob.cancel() }
            if (::facade.isInitialized) attempt { check(facade.retireForPanic()) }
            attempt { facadeJob.cancel() }
            reapJob(scopeJob)
            reapJob(coreHostJob)
            reapJob(facadeJob)
            attempt { healthy() }
            val first = failures.firstOrNull()
            if (first != null) {
                val seen = java.util.IdentityHashMap<Throwable, Unit>()
                seen[first] = Unit
                failures.drop(1).forEach { if (seen.put(it, Unit) == null) first.addSuppressed(it) }
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
        private fun packet() = BitchatPacket(
            version = 1u, type = com.bitchat.android.protocol.MessageType.MESSAGE.value,
            senderID = PEER_ID.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            recipientID = com.bitchat.android.protocol.SpecialRecipients.BROADCAST, timestamp = 1uL,
            payload = byteArrayOf(1), ttl = 0u
        )
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
