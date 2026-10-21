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

/** Real StoreForward roots/owned cached-send callbacks; synthetic parents never instantiate a runtime. */
class PanicOriginalStoreForwardRootsTest {
    @Test fun heldOriginalBluetoothStoreSendKeepsUnionConsumerPending() = runBlocking {
        val post = withFixture { f ->
            val store = f.stores.first()
            val held = f.holdStore(store)
            f.ready(held.entered)
            f.retire()
            val expected = f.expectCompleteUnionBeforeLaunch()
            val call = f.launchCollector()
            val original = store.captureOriginalStoreForwardBinding()
            check(originalStoreForwardShutdownRequested(original))
            check(store.getCachedMessageCount(PEER_ID) == 1) // A request did not clear the held packet.
            val independent = f.launchStoreAwait(store)
            check(!independent.isCompleted && !held.returned.get() && held.release.count == 1L)
            val completedBeforeRelease = call.job.isCompleted
            f.healthy()
            return@withFixture {
                f.requireCompletePreparationPhase(checkNotNull(call.result.get()), expected)
                assertFalse("The union consumer must await the held original Bluetooth-parent Store send", completedBeforeRelease)
                assertTrue(f.evidence(checkNotNull(call.result.get())).storeForwardObservation.awaitReturnedRoots.any { it === original.identity })
            }
        }
        post()
    }

    @Test fun heldOriginalCoreStoreSendKeepsUnionConsumerPending() = runBlocking {
        val post = withFixture { f ->
            val core = f.newCore()
            val store = core.store()
            val held = f.holdStore(store)
            f.ready(held.entered)
            f.retire()
            val expected = f.expectCompleteUnionBeforeLaunch()
            val call = f.launchCollector()
            val original = store.captureOriginalStoreForwardBinding()
            check(originalStoreForwardShutdownRequested(original))
            check(store.getCachedMessageCount(PEER_ID) == 1) // A request did not clear the held packet.
            val independent = f.launchStoreAwait(store)
            check(!independent.isCompleted && !held.returned.get() && held.release.count == 1L)
            val completedBeforeRelease = call.job.isCompleted
            f.healthy()
            return@withFixture {
                f.requireCompletePreparationPhase(checkNotNull(call.result.get()), expected)
                assertFalse("The union consumer must await the held real Core Store send", completedBeforeRelease)
                assertTrue(f.evidence(checkNotNull(call.result.get())).storeForwardObservation.awaitReturnedRoots.any { it === original.identity })
            }
        }
        post()
    }

    @Test fun allProcessorAndStoreRequestsPrecedeFacadeAndAnyJoin() = runBlocking {
        val post = withFixture { f ->
            val core = f.newCore()
            val heldPP = f.holdBluetooth()
            f.bluetoothProcessor.processPacket(RoutedPacket(packet(), PEER_ID, ingressLinkID = "synthetic-link"))
            f.ready(heldPP.entered)
            val heldStore = f.holdStore(core.store())
            f.ready(heldStore.entered)
            val atDetach = mutableListOf<Pair<List<Boolean>, List<Boolean>>>()
            f.facade.delegate = mock<MeshDelegate>()
            check(f.transport.delegate === f.facade)
            f.completeFacadeRootOnly()
            f.transport.onDetach = { atDetach += f.requestFlags() }
            f.fence()
            val call = f.launchCollector()
            val observedDetach = atDetach.toList()
            check(!heldPP.returned.get() && !heldStore.returned.get())
            val pendingAfterDetach = !call.job.isCompleted
            f.healthy()
            return@withFixture {
                assertEquals(listOf(listOf(true, true) to listOf(true, true)), observedDetach)
                assertTrue(pendingAfterDetach)
                val report = checkNotNull(call.result.get())
                val e = f.evidence(report)
                assertEquals(listOf(HolderProcessorSource.BluetoothService, HolderProcessorSource.WifiCore), e.processorObservation.rows.map { it.source })
                assertEquals(listOf(HolderStoreForwardSource.BluetoothService, HolderStoreForwardSource.WifiCore), e.storeForwardObservation.rows.map { it.source })
                f.assertComplete(e)
                f.assertUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun completedStoreRequestsPreserveUnconfirmedUnion() = runBlocking {
        val post = withFixture { f ->
            f.newCore()
            f.retire()
            val report = f.collect()
            val e = f.evidence(report)
            return@withFixture {
                assertEquals(2, e.storeForwardObservation.requests.size)
                f.assertComplete(e)
                f.assertUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun latePreacceptedStoreCreationRequiresWholeUnionRequestRetry() = runBlocking {
        val post = withFixture { f ->
            // Generic Holder API order only; real BLE/Core constructors remain Store-before-PP.
            val held = f.holdBluetooth()
            f.bluetoothProcessor.processPacket(RoutedPacket(packet(), PEER_ID, ingressLinkID = "synthetic-link"))
            f.ready(held.entered)
            val creatorEntered = CountDownLatch(1)
            val allowStore = f.gate()
            val acquired = AtomicBoolean(false)
            val lateStore = AtomicReference<StoreForwardManager>()
            f.transport.reusable = false
            val worker = f.worker {
                val product = f.holder.getOrCreate { context ->
                    f.createBluetooth(context, beforeStore = { creatorEntered.countDown(); f.waitGate(allowStore) },
                        returnedStore = { lateStore.set(it) })
                }
                check(product is HolderAcquisitionResult.Available)
                acquired.set(true)
            }
            f.await(creatorEntered)
            check(f.processors.size == 2 && f.stores.size == 1)
            f.facade.delegate = mock<MeshDelegate>()
            check(f.transport.delegate === f.facade)
            f.completeFacadeRootOnly()
            val hookReturned = AtomicBoolean(false)
            val requestedAtHook = AtomicReference<List<Boolean>>()
            f.transport.onDetach = {
                requestedAtHook.set(f.requestFlags().first)
                allowStore.countDown()
                f.reap(worker)
                check(acquired.get() && lateStore.get() != null)
                hookReturned.set(true)
            }
            f.fence()
            val first = f.launchCollector()
            val completedBeforePPRelease = first.job.isCompleted
            check(hookReturned.get() && acquired.get() && !worker.isAlive)
            check(requestedAtHook.get() == listOf(true, true))
            check(!held.returned.get() && held.release.count == 1L)
            val late = checkNotNull(lateStore.get()).captureOriginalStoreForwardBinding()
            check(!originalStoreForwardShutdownRequested(late))
            val ready = f.captureUnion()
            check(ready.processorBatch.gaps.isEmpty() && ready.storeForwardGaps.isEmpty())
            check(ready.processorBatch.roots.size == 2 && f.requestFlags().first == listOf(true, true))
            held.release.countDown()
            f.awaitProcessor(f.bluetoothProcessor)
            f.join(first.job)
            val firstReport = checkNotNull(first.result.get())
            val secondReport = f.collect()
            val e1 = f.evidence(firstReport)
            val e2 = f.evidence(secondReport)
            val lateRequestedOnRetry = originalStoreForwardShutdownRequested(late)
            return@withFixture {
                assertTrue("A Store-only missing request must reject the whole union BEFORE the held PP join", completedBeforePPRelease)
                assertEquals(PanicStoreForwardBatchStatus.NeedsExplicitRetry, e1.storeForwardObservation.status)
                assertTrue(e1.processorObservation.awaitReturnedRoots.isEmpty())
                assertEquals(1, e1.storeForwardObservation.requests.size)
                assertTrue(lateRequestedOnRetry)
                assertEquals(2, e2.storeForwardObservation.requests.size)
                f.assertComplete(e2)
                f.assertUnconfirmed(firstReport)
                f.assertUnconfirmed(secondReport)
            }
        }
        post()
    }

    @Test fun returnedStoreUnderUnknownCoreParentBlocksCombinedAwait() = runBlocking {
        val post = withFixture { f ->
            val sentinel = IllegalStateException("synthetic later Core setter failure")
            val core = f.newCore(setterFailure = sentinel)
            check(core.failure.get() === sentinel && core.setterMatchesOriginal())
            f.retire()
            val report = f.collect()
            val e = f.evidence(report)
            val storeRoot = core.store().captureOriginalStoreForwardBinding()
            val requestedBeforeCleanup = originalStoreForwardShutdownRequested(storeRoot)
            return@withFixture {
                assertSame(sentinel, core.failure.get())
                assertTrue(requestedBeforeCleanup)
                assertTrue(e.storeForwardObservation.requests.any { it.root === storeRoot.identity && it.status == PanicProcessorRequestStatus.RequestReturned })
                assertTrue(HolderProcessorGap.UnknownParent in e.processorObservation.holderGaps)
                assertEquals(HolderStoreForwardState.Returned, e.storeForwardObservation.rows.single { it.source == HolderStoreForwardSource.WifiCore }.state)
                assertEquals(PanicStoreForwardBatchStatus.Ineligible, e.storeForwardObservation.status)
                assertTrue(e.processorObservation.awaitReturnedRoots.isEmpty() && e.storeForwardObservation.awaitReturnedRoots.isEmpty())
                f.assertUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun foreignWrongKindAndEndedContextsRunNoStoreCreator() = runBlocking {
        val post = withFixture { f ->
            val core = f.newCore()
            val other = f.otherHolder()
            lateinit var foreign: HolderWifiConstructionContext
            check(other.constructWifi(other.initialProcessPreparation().origin, Any()) { foreign = it; Unit } is WifiAwareFactoryResult.Created)
            f.retire()
            var creators = 0
            fun denied(context: HolderConstructionContext): HolderContextOperationRefusal {
                try { f.holder.constructBluetoothStoreForward(context) { creators++; error("Refused creator must not run") } }
                catch (failure: HolderContextOperationRefusal) { return failure }
                error("Actual Store context guard must refuse")
            }
            val foreignRefusal = denied(foreign)
            val wrongKind = denied(checkNotNull(core.context.get()))
            val ended = denied(f.bluetoothContext)
            return@withFixture {
                assertEquals(0, creators)
                assertEquals(HolderContextOperationGap.ForeignContext, foreignRefusal.gap)
                assertEquals(HolderContextOperationGap.WrongContextKind, wrongKind.gap)
                assertEquals(HolderContextOperationGap.EndedContextAfterClose, ended.gap)
            }
        }
        post()
    }

    @Test fun processorScopesRemainSeparateFromStoreUnion() = runBlocking {
        val post1 = withFixture { f ->
            f.newCore()
            f.retire()
            val ble = f.captureBluetooth()
            val oldUnion = f.captureProcessors()
            val combined = f.captureUnion()
            val oldReport = collectPanicRetirementWithOriginalBluetoothAndCoreProcessors(f.facade, PanicOriginalGossipInput.Installed(f.binding, f.facade))
            val oldEvidence = ((oldReport.transportCoreRadioAndHandlers as PanicOwnerRetirement.Unconfirmed).evidence as
                PanicUnconfirmedEvidence.OriginalBluetoothAndCoreProcessors).observation
            return@withFixture {
                assertEquals(1, ble.roots.size)
                assertEquals(2, oldUnion.roots.size)
                assertEquals(2, combined.processorBatch.roots.size)
                assertEquals(2, combined.storeForwardRoots.size)
                assertFalse(validateHolderOriginalBluetoothAndCoreProcessors(f.binding, f.facade, ble))
                assertFalse(validateHolderOriginalBluetoothProcessors(f.binding, f.facade, oldUnion))
                assertEquals(PanicProcessorBatchStatus.AwaitReturned, oldEvidence.status)
                // Opaque old batch types cannot be passed to the new combined validator.
                f.assertUnconfirmed(oldReport)
            }
        }
        val post2 = withFixture(registerStores = false) { f ->
            f.retire()
            val old = f.captureProcessors()
            val combined = f.captureUnion()
            val validOld = validateHolderOriginalBluetoothAndCoreProcessors(f.binding, f.facade, old)
            val validCombined = validateHolderOriginalProcessorStoreForwardUnion(f.binding, f.facade, combined)
            return@withFixture {
                assertTrue(validOld)
                assertFalse(validCombined)
                assertTrue(HolderStoreForwardGap.MissingStoreForward in combined.storeForwardGaps)
            }
        }
        post1()
        post2()
    }

    @Test fun cancelledOrChangedCallerCannotPromoteStoreAwait() = runBlocking {
        val post = withFixture { f ->
            f.retire()
            val sentinel = CancellationException("synthetic original Store caller cancellation")
            val cancelled = AtomicReference<Throwable>()
            val forbiddenResult = AtomicReference<PanicRetirementReport>()
            val caller = f.own(f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                checkNotNull(currentCoroutineContext()[Job]).cancel(sentinel)
                try { forbiddenResult.set(f.collect()) } catch (failure: CancellationException) { cancelled.set(failure) }
            })
            f.join(caller)
            val crossResult = AtomicReference<Boolean>()
            val changed = f.own(f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val original = f.stores.first().captureOriginalStoreForwardBinding()
                val capturedCaller = captureOriginalStoreForwardCaller()
                check(requestOriginalStoreForwardShutdown(original))
                val prepared = prepareOriginalStoreForwardAwait(original, capturedCaller)
                check(prepared is OriginalStoreForwardPreparation.Ready)
                val other = f.own(f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    crossResult.set(awaitOriginalStoreForward(prepared.preparation))
                })
                other.join()
            })
            f.join(changed)
            return@withFixture {
                assertSame(sentinel, cancelled.get())
                assertNull(forbiddenResult.get())
                assertEquals(false, crossResult.get())
            }
        }
        post()
    }

    @Test fun retainedReplacementStoresKeepOriginalRootsWithoutAggregateConfirmation() = runBlocking {
        val post = withFixture { f ->
            f.newCore()
            f.replaceBluetooth()
            f.newCore()
            f.holder.clear()
            f.retire()
            val report = f.collect()
            val e = f.evidence(report)
            return@withFixture {
                assertEquals(4, e.storeForwardObservation.rows.size)
                assertEquals(2, e.storeForwardObservation.rows.count { it.source == HolderStoreForwardSource.BluetoothService })
                assertEquals(2, e.storeForwardObservation.rows.count { it.source == HolderStoreForwardSource.WifiCore })
                assertEquals(4, e.storeForwardObservation.rows.map { it.child }.distinct().size)
                assertEquals(4, e.storeForwardObservation.rows.map { it.root }.distinct().size)
                assertEquals(listOf(true, true, true, true), f.requestFlags().second)
                f.assertComplete(e)
                f.assertUnconfirmed(report)
            }
        }
        post()
    }

    private class Call(val job: Job, val result: AtomicReference<PanicRetirementReport>)
    /** Scalar expected identities only; no preparation, root handle or restored authority. */
    private class CompleteUnionExpectation(
        val processorRows: List<HolderProcessorRow>, val storeRows: List<HolderStoreForwardRow>,
        val processorRoots: List<OriginalProcessorRootIdentity>, val storeRoots: List<OriginalStoreForwardRootIdentity>
    )
    private class Held(val entered: CompletableDeferred<Unit>, val release: CountDownLatch, val returned: AtomicBoolean)
    private class CoreRecord {
        val context = AtomicReference<HolderWifiConstructionContext>()
        val failure = AtomicReference<Throwable>()
        private val actualCore = AtomicReference<MeshCore>()
        private val actualProcessor = AtomicReference<PacketProcessor>()
        private val actualStore = AtomicReference<StoreForwardManager>()
        private val actualSetterProcessor = AtomicReference<PacketProcessor>()
        @Volatile var validation: (BitchatPacket) -> Boolean = { false }
        fun core(): MeshCore = checkNotNull(actualCore.get())
        fun installCore(core: MeshCore) { check(actualCore.compareAndSet(null, core)) }
        fun store(): StoreForwardManager = checkNotNull(actualStore.get())
        fun installStore(store: StoreForwardManager) { check(actualStore.compareAndSet(null, store)) }
        fun processorOrNull(): PacketProcessor? = actualProcessor.get()
        fun processor(): PacketProcessor = checkNotNull(processorOrNull())
        fun installProcessor(processor: PacketProcessor) { check(actualProcessor.compareAndSet(null, processor)) }
        fun captureSetter(processor: PacketProcessor) { check(actualSetterProcessor.compareAndSet(null, processor)) }
        fun setterMatchesOriginal(): Boolean = actualSetterProcessor.get() === processorOrNull() && processorOrNull() != null
        fun binding(): OriginalProcessorBinding = processor().captureOriginalProcessorBinding()
    }

    private class Fixture(private val registerStores: Boolean) {
        val stores = CopyOnWriteArrayList<StoreForwardManager>()
        val processors = CopyOnWriteArrayList<PacketProcessor>()
        private val knownJobs = CopyOnWriteArrayList<Job>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val gates = CopyOnWriteArrayList<CountDownLatch>()
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
        fun createBluetooth(
            context: HolderConstructionContext, beforeStore: () -> Unit = {}, returnedStore: (StoreForwardManager) -> Unit = {}
        ): InertMesh {
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
            val creator = { beforeStore(); StoreForwardManager().also { stores += it } }
            val store = if (registerStores) holder.constructBluetoothStoreForward(context, creator) else creator()
            returnedStore(store)
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
            record: CoreRecord, setterFailure: Throwable? = null
        ): Thread = worker {
            try {
                val result = holder.constructWifi(original, Any()) { context ->
                    check(record.context.compareAndSet(null, context))
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
                controllers += Mockito.mockConstruction(MessageHandler::class.java) { instance, _ ->
                    doAnswer { call ->
                        val actual = call.getArgument<PacketProcessor>(0)
                        record.captureSetter(actual)
                        check(actual === record.processor())
                        if (setterFailure != null) throw setterFailure
                        null
                    }.whenever(instance).packetProcessor = any()
                }
                check(controllers.size == 5 && Thread.currentThread() === installationThread)
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
                    recordFragmentManager = { creator -> creator() },
                    recordPeerManager = { creator -> creator() },
                    recordStoreForwardManager = { creator ->
                        holder.constructWifiStoreForward(context) { creator().also { stores += it } }.also(record::installStore)
                    },
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
                    try { check(controllers.size == 5 && controllers.all { it.constructed().size == 1 }) }
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
        fun holdBluetooth(): Held = Held(CompletableDeferred(), gate(), AtomicBoolean(false)).also { held ->
            bluetoothValidation = { held.entered.complete(Unit); waitGate(held.release); held.returned.set(true); false }
        }
        fun fence() { check(PanicResetCoordinator.fenceCapturedPanicOwners(facade, binding, facade) === HolderConstructionCloseOutcome.ClosedForConstruction) }
        suspend fun retire() {
            fence()
            check(facade.retireForPanic() && facadeJob.isCompleted)
            processors.forEach { it.shutdown() }
            processors.forEach { awaitProcessor(it) }
        }
        suspend fun collect(): PanicRetirementReport = collectPanicRetirementWithOriginalProcessorStoreForwardUnion(
            facade, PanicOriginalGossipInput.Installed(binding, facade))
        fun launchCollector(): Call {
            val result = AtomicReference<PanicRetirementReport>()
            val job = own(scope.launch(start = CoroutineStart.UNDISPATCHED) { result.set(collect()) })
            return Call(job, result)
        }
        fun captureBluetooth(): HolderProcessorBatch = (captureHolderOriginalBluetoothProcessors(binding, facade) as HolderProcessorCapture.Observed).batch
        fun captureProcessors(): HolderProcessorBatch = (captureHolderOriginalBluetoothAndCoreProcessors(binding, facade) as HolderProcessorCapture.Observed).batch
        fun captureUnion(): HolderProcessorStoreForwardBatch = (captureHolderOriginalProcessorStoreForwardUnion(binding, facade) as HolderProcessorStoreForwardCapture.Observed).batch
        fun expectCompleteUnionBeforeLaunch(): CompleteUnionExpectation {
            val actual = captureUnion()
            check(actual.processorBatch.gaps.isEmpty() && actual.storeForwardGaps.isEmpty())
            check(validateHolderOriginalProcessorStoreForwardUnion(binding, facade, actual))
            check(actual.processorBatch.roots.isNotEmpty() && actual.storeForwardRoots.isNotEmpty())
            return CompleteUnionExpectation(actual.processorBatch.rows, actual.storeForwardRows,
                actual.processorBatch.roots.map { it.identity }, actual.storeForwardRoots.map { it.identity })
        }
        /** Called only by the two post-cleanup closures, BEFORE either saved-progress fatal. */
        fun requireCompletePreparationPhase(report: PanicRetirementReport, expected: CompleteUnionExpectation) {
            check(report.facade === PanicOwnerRetirement.Confirmed)
            val actual = evidence(report)
            val pp = actual.processorObservation
            val sf = actual.storeForwardObservation
            check(pp.rows == expected.processorRows && sf.rows == expected.storeRows)
            check(pp.holderGaps.isEmpty() && pp.callerGaps.isEmpty() && sf.holderGaps.isEmpty() && sf.callerGaps.isEmpty())
            check(pp.requests.map { it.root } == expected.processorRoots && pp.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
            check(sf.requests.map { it.root } == expected.storeRoots && sf.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
            check(pp.status == PanicProcessorBatchStatus.AwaitReturned && pp.awaitReturnedRoots == expected.processorRoots)
            // The producer emits RequestedNotAwaited only AFTER all authentic Store preparations
            // validate and every old processor await returns. No separate prepared-ID field exists.
            check(sf.status == PanicStoreForwardBatchStatus.RequestedNotAwaited || sf.status == PanicStoreForwardBatchStatus.AwaitReturned)
            when (sf.status) {
                PanicStoreForwardBatchStatus.RequestedNotAwaited -> check(sf.awaitReturnedRoots.isEmpty())
                PanicStoreForwardBatchStatus.AwaitReturned -> check(sf.awaitReturnedRoots == expected.storeRoots)
                else -> error("Early Store ineligibility cannot reach the saved-progress policy assertion")
            }
        }
        fun evidence(report: PanicRetirementReport): PanicUnconfirmedEvidence.OriginalProcessorStoreForwardUnion =
            (report.transportCoreRadioAndHandlers as PanicOwnerRetirement.Unconfirmed).evidence as PanicUnconfirmedEvidence.OriginalProcessorStoreForwardUnion
        fun requestFlags() = processors.map { it.originalShutdownRequested(it.captureOriginalProcessorBinding()) } to
            stores.map { originalStoreForwardShutdownRequested(it.captureOriginalStoreForwardBinding()) }
        fun assertComplete(e: PanicUnconfirmedEvidence.OriginalProcessorStoreForwardUnion) {
            val pp = e.processorObservation
            val sf = e.storeForwardObservation
            assertTrue(pp.holderGaps.isEmpty() && pp.callerGaps.isEmpty() && sf.holderGaps.isEmpty() && sf.callerGaps.isEmpty())
            assertEquals(PanicProcessorBatchStatus.AwaitReturned, pp.status)
            assertEquals(pp.rows.map { it.root }, pp.awaitReturnedRoots)
            assertEquals(sf.rows.map { it.root }, sf.requests.map { it.root })
            assertTrue(sf.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
            when (sf.status) {
                PanicStoreForwardBatchStatus.RequestedNotAwaited -> assertTrue(sf.awaitReturnedRoots.isEmpty())
                PanicStoreForwardBatchStatus.AwaitReturned -> assertEquals(sf.rows.map { it.root }, sf.awaitReturnedRoots)
                else -> fail("The complete pass must truthfully distinguish request from actual Store await")
            }
        }
        suspend fun completeFacadeRootOnly() { facadeJob.cancel(); join(facadeJob); check(facadeJob.isCompleted) }
        fun holdStore(store: StoreForwardManager): Held = Held(CompletableDeferred(), gate(), AtomicBoolean(false)).also { held ->
            store.delegate = object : StoreForwardManagerDelegate {
                override fun isFavorite(peerID: String) = false
                override fun isPeerOnline(peerID: String) = false
                override fun sendPacket(packet: BitchatPacket) {
                    held.entered.complete(Unit)
                    waitGate(held.release)
                    held.returned.set(true)
                }
            }
            store.cacheMessage(storePacket(), "synthetic-stored-message")
            store.sendCachedMessages(PEER_ID)
        }
        fun launchStoreAwait(store: StoreForwardManager): Job = own(scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val original = store.captureOriginalStoreForwardBinding()
            val prepared = prepareOriginalStoreForwardAwait(original, captureOriginalStoreForwardCaller())
            check(prepared is OriginalStoreForwardPreparation.Ready)
            check(awaitOriginalStoreForward(prepared.preparation))
        })
        private suspend fun joinStore(store: StoreForwardManager) {
            val original = store.captureOriginalStoreForwardBinding()
            val prepared = prepareOriginalStoreForwardAwait(original, captureOriginalStoreForwardCaller())
            check(prepared is OriginalStoreForwardPreparation.Ready)
            check(awaitOriginalStoreForward(prepared.preparation))
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
        fun waitGate(gate: CountDownLatch) {
            try { if (gate.await(GUARD_MS, TimeUnit.MILLISECONDS)) return }
            catch (failure: InterruptedException) { errors += failure }
            errors += AssertionError("Owned callback or constructor exceeded its first readiness guard")
            while (gate.count != 0L) {
                try { gate.await() } catch (failure: InterruptedException) { errors += failure }
            }
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
            stores.forEach { store -> attempt { check(requestOriginalStoreForwardShutdown(store.captureOriginalStoreForwardBinding())) } }
            stores.forEach { store ->
                attempt { withTimeout(GUARD_MS) { joinStore(store) } }
                attempt { joinStore(store) }
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
        private fun storePacket() = BitchatPacket(
            version = 1u, type = com.bitchat.android.protocol.MessageType.MESSAGE.value,
            senderID = MY_ID.toByteArray(), recipientID = PEER_ID.toByteArray(), timestamp = 1uL,
            payload = byteArrayOf(1), ttl = 0u
        )
        private suspend fun withFixture(registerStores: Boolean = true, block: suspend (Fixture) -> (() -> Unit)): () -> Unit {
            val fixture = Fixture(registerStores)
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
