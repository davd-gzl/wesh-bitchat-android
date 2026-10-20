package com.bitchat.android.ui

import android.content.Context
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.mesh.*
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.service.*
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.wifiaware.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Original Peer roots and real periodic callbacks; no ordinary Core/Peer teardown or global clear. */
@OptIn(ExperimentalCoroutinesApi::class)
class PanicOriginalPeerRootsTest {
    @Test fun heldOriginalBluetoothPeerRemovalKeepsUnionConsumerPending() = runBlocking {
        val post = withFixture { f ->
            f.createController()
            val peer = f.peers.first()
            val held = f.holdNaturalRemoval(peer)
            f.periodicReady(held.entered)
            val otherPeersCompleted = f.prepareEarlier(heldPeer = peer)
            val expected = f.completeExpected(full = true)
            val call = f.launchCollector(full = true)
            val completedBeforeRelease = call.job.isCompleted
            val requested = originalPeerShutdownRequested(peer.captureOriginalPeerBinding())
            val retained = f.seedOwnPeer(peer)
            val probe = f.launchPeerProbe(peer)
            val probePending = !probe.job.isCompleted && probe.prepared.get() && !held.returned.get()
            check(requested && peer.getPeerInfo(retained) != null && held.release.count == 1L)
            return@withFixture {
                f.requireCompleteCall(checkNotNull(call.result.get()), expected, full = true)
                check(otherPeersCompleted == expected.peers.filter { it !== peer.captureOriginalPeerBinding().identity })
                check(probePending && probe.returned.get() && held.returned.get() && probe.failure.get() == null)
                assertFalse("The full union consumer must await its held original Bluetooth Peer callback", completedBeforeRelease)
            }
        }
        post()
    }

    @Test fun heldOriginalCorePeerRemovalKeepsUnionConsumerPending() = runBlocking {
        val post = withFixture { f ->
            val core = CoreRecord()
            f.createController(core = core)
            check(core.setterMatchesOriginal() && core.createdOrder == listOf("Peer", "Store", "Processor"))
            val peer = core.peer()
            val held = f.holdNaturalRemoval(peer)
            f.periodicReady(held.entered)
            val otherPeersCompleted = f.prepareEarlier(heldPeer = peer)
            val expected = f.completeExpected(full = true)
            val call = f.launchCollector(full = true)
            val completedBeforeRelease = call.job.isCompleted
            val retained = f.seedOwnPeer(peer)
            val probe = f.launchPeerProbe(peer)
            val probePending = !probe.job.isCompleted && probe.prepared.get() && !held.returned.get()
            check(originalPeerShutdownRequested(peer.captureOriginalPeerBinding()) && peer.getPeerInfo(retained) != null)
            return@withFixture {
                f.requireCompleteCall(checkNotNull(call.result.get()), expected, full = true)
                check(otherPeersCompleted == expected.peers.filter { it !== peer.captureOriginalPeerBinding().identity })
                check(probePending && probe.returned.get() && held.returned.get() && probe.failure.get() == null)
                assertFalse("The full union consumer must await the real Core's held original Peer callback", completedBeforeRelease)
            }
        }
        post()
    }

    @Test fun allProcessorStoreAndPeerRequestsPrecedeFacadeAndCoordinatorJoins() = runBlocking {
        val post = withFixture { f ->
            f.createController()
            f.completeFacadeRootOnly()
            val observed = AtomicReference<Triple<List<Boolean>, List<Boolean>, List<Boolean>>>()
            f.transport.onDetach = { observed.set(f.requestFlags()) }
            f.fence()
            val call = f.launchCollector(full = true)
            f.join(call.job)
            val expected = f.completeExpected(full = true)
            return@withFixture {
                val flags = checkNotNull(observed.get())
                assertEquals(List(expected.processors.size) { true }, flags.first)
                assertEquals(List(expected.stores.size) { true }, flags.second)
                assertEquals(List(expected.peers.size) { true }, flags.third)
                f.requireCompleteCall(checkNotNull(call.result.get()), expected, full = true)
            }
        }
        post()
    }

    @Test fun completedPeerRootsPreserveUnconfirmedUnion() = runBlocking {
        val post = withFixture { f ->
            f.createController()
            f.prepareEarlier()
            f.requestAndJoinPeers()
            val expected = f.completeExpected(full = true)
            val report = f.collect(full = true)
            return@withFixture {
                f.requireCompleteCall(report, expected, full = true)
                f.requireUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun latePreacceptedPeerCreationRequiresWholeUnionRetryBeforeAnyEarlierWait() = runBlocking {
        val post = withFixture { f ->
            // Generic Holder order ONLY: PP+Store then Peer; not the real Core allocation order.
            val held = f.holdBluetoothProcessor()
            f.processors.first().processPacket(RoutedPacket(packet(), PEER_ID, ingressLinkID = "synthetic-link"))
            f.ready(held.entered)
            val entered = CountDownLatch(1)
            val release = f.threadGate()
            val returned = AtomicBoolean()
            val late = AtomicReference<PeerManager>()
            f.transport.reusable = false
            val worker = f.worker {
                check(f.holder.getOrCreate { context -> f.createBluetooth(context, beforePeer = {
                    entered.countDown(); f.waitGate(release)
                }, peerReturned = { late.set(it) }) } is HolderAcquisitionResult.Available)
                returned.set(true)
            }
            f.await(entered)
            check(f.processors.size == 2 && f.stores.size == 2 && f.peers.size == 1)
            f.completeFacadeRootOnly() // Leaves retirement/delegate hook genuinely unclaimed.
            val hookReturned = AtomicBoolean()
            val ppFlags = AtomicReference<List<Boolean>>()
            f.transport.onDetach = {
                ppFlags.set(f.requestFlags().first)
                release.countDown(); f.reap(worker)
                check(returned.get() && late.get() != null)
                hookReturned.set(true)
            }
            f.fence()
            val first = f.launchCollector(full = false)
            val completedBeforePPRelease = first.job.isCompleted
            val firstReport = checkNotNull(first.result.get())
            val lateBinding = checkNotNull(late.get()).captureOriginalPeerBinding()
            val lateWasUnrequested = !originalPeerShutdownRequested(lateBinding)
            val coherent = f.completeExpected(full = false)
            check(hookReturned.get() && returned.get() && !worker.isAlive)
            check(ppFlags.get() == listOf(true, true) && !held.returned.get() && held.release.count == 1L)
            held.release.countDown()
            f.awaitProcessor(f.processors.first())
            f.join(first.job)
            val retry = f.collect(full = false)
            return@withFixture {
                f.requireLatePeerOnlyGap(firstReport, coherent, lateBinding.identity)
                check(lateWasUnrequested && hookReturned.get())
                assertTrue("The lower whole-union gate must return before ANY held earlier PP await", completedBeforePPRelease)
                f.requireCompleteCall(retry, coherent, full = false)
            }
        }
        post()
    }

    @Test fun returnedPeerUnderUnknownCoreParentBlocksCombinedAwait() = runBlocking {
        val post = withFixture { f ->
            val sentinel = IllegalStateException("synthetic Core setter failure")
            val record = CoreRecord()
            f.newUnknownCore(record, sentinel)
            check(record.failure.get() === sentinel && record.setterMatchesOriginal())
            f.fence()
            val report = f.collect(full = false)
            val requested = originalPeerShutdownRequested(record.peer().captureOriginalPeerBinding())
            return@withFixture {
                check(requested)
                assertEquals(PanicPeerBatchStatus.Ineligible, report.originalPeers.status)
                assertTrue(report.originalPeers.awaitReturnedRoots.isEmpty())
                assertTrue(report.originalPeers.rows.any { it.root === record.peer().captureOriginalPeerBinding().identity && it.state == HolderPeerState.Returned })
                val old = f.evidence(report)
                assertTrue(HolderProcessorGap.UnknownParent in old.processorObservation.holderGaps)
                assertTrue(old.processorObservation.awaitReturnedRoots.isEmpty() && old.storeForwardObservation.awaitReturnedRoots.isEmpty())
                f.requireUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun foreignWrongKindAndEndedContextsRunNoPeerCreator() = runBlocking {
        val post = withFixture { f ->
            val record = CoreRecord()
            f.createController(core = record)
            val other = f.otherHolder()
            lateinit var foreign: HolderWifiConstructionContext
            check(other.constructWifi(other.initialProcessPreparation().origin, Any()) { foreign = it; Unit } is WifiAwareFactoryResult.Created)
            f.fence()
            var creators = 0
            fun denied(context: HolderConstructionContext): HolderContextOperationRefusal {
                try { f.holder.constructBluetoothPeer(context) { creators++; error("Refused Peer creator ran") } }
                catch (failure: HolderContextOperationRefusal) { return failure }
                error("Context must be refused before allocation")
            }
            val foreignFailure = denied(foreign)
            val wrongKind = denied(checkNotNull(record.context.get()))
            val ended = denied(f.bluetoothContext)
            return@withFixture {
                assertEquals(0, creators)
                assertEquals(HolderContextOperationGap.ForeignContext, foreignFailure.gap)
                assertEquals(HolderContextOperationGap.WrongContextKind, wrongKind.gap)
                assertEquals(HolderContextOperationGap.EndedContextAfterClose, ended.gap)
            }
        }
        post()
    }

    @Test fun oldProcessorStoreScopesRemainSeparateFromPeerUnion() = runBlocking {
        val post = withFixture(registerPeers = false) { f ->
            f.createController()
            f.prepareEarlier()
            val input = PanicOriginalGossipInput.Installed(f.binding, f.facade)
            val oldBle = collectPanicRetirementWithOriginalBluetoothProcessors(f.facade, input)
            val oldCore = collectPanicRetirementWithOriginalBluetoothAndCoreProcessors(f.facade, input)
            val oldStore = collectPanicRetirementWithOriginalProcessorStoreForwardUnion(f.facade, input)
            val oldWifi = collectPanicRetirementWithOriginalWifiHosts(f.facade, input)
            val requiredPeer = f.collect(full = true)
            return@withFixture {
                listOf(oldBle, oldCore, oldStore, oldWifi).forEach {
                    assertEquals(PanicPeerBatchStatus.NotCollected, it.originalPeers.status)
                    f.requireUnconfirmed(it)
                }
                assertEquals(PanicProcessorBatchStatus.AwaitReturned,
                    ((oldBle.transportCoreRadioAndHandlers as PanicOwnerRetirement.Unconfirmed).evidence as PanicUnconfirmedEvidence.OriginalBluetoothProcessors).observation.status)
                assertEquals(PanicProcessorBatchStatus.AwaitReturned,
                    ((oldCore.transportCoreRadioAndHandlers as PanicOwnerRetirement.Unconfirmed).evidence as PanicUnconfirmedEvidence.OriginalBluetoothAndCoreProcessors).observation.status)
                assertEquals(PanicStoreForwardBatchStatus.AwaitReturned, f.evidence(oldStore).storeForwardObservation.status)
                assertEquals(WifiHostBatchStatus.AwaitReturned, oldWifi.originalWifiHosts.status)
                assertEquals(PanicPeerBatchStatus.Ineligible, requiredPeer.originalPeers.status)
                assertTrue(HolderPeerGap.MissingPeer in requiredPeer.originalPeers.holderGaps)
            }
        }
        post()
    }

    @Test fun cancelledOrChangedCallerCannotPromotePeerAwait() = runBlocking {
        val post = withFixture { f ->
            val held = f.holdBluetoothProcessor()
            f.processors.first().processPacket(RoutedPacket(packet(), PEER_ID, ingressLinkID = "synthetic-link"))
            f.ready(held.entered)
            f.completeFacadeRootOnly()
            f.fence()
            val sentinel = CancellationException("synthetic Peer caller cancellation")
            val first = f.launchCollector(full = false, expectedFailure = sentinel)
            val pendingAtExistingPPWait = !first.job.isCompleted && !held.returned.get() && held.release.count == 1L
            check(f.requestFlags().let { it.first.all { value -> value } && it.second.all { value -> value } && it.third.all { value -> value } })
            first.job.cancel(sentinel)
            f.join(first.job)
            val changed = AtomicReference<Boolean>()
            val outer = f.own(f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val binding = f.peers.first().captureOriginalPeerBinding()
                val caller = captureOriginalPeerCaller()
                check(requestOriginalPeerShutdown(binding))
                val prepared = prepareOriginalPeerAwait(binding, caller)
                check(prepared is OriginalPeerPreparation.Ready)
                val inner = f.own(f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    changed.set(awaitOriginalPeer(prepared.preparation))
                })
                inner.join()
            })
            f.join(outer)
            return@withFixture {
                check(pendingAtExistingPPWait)
                assertSame(sentinel, first.failure.get())
                assertNull(first.result.get())
                assertEquals(false, changed.get())
            }
        }
        post()
    }

    @Test fun replacedPeerManagersKeepOriginalRootsWithoutAggregateConfirmation() = runBlocking {
        val post = withFixture { f ->
            val first = f.peers.first().captureOriginalPeerBinding()
            f.transport.reusable = false
            check(f.holder.getOrCreate { f.createBluetooth(it) } is HolderAcquisitionResult.Available)
            val second = f.peers.last().captureOriginalPeerBinding()
            check(first !== second)
            f.holder.clear()
            f.fence()
            val report = f.collect(full = false)
            val expected = f.completeExpected(full = false)
            return@withFixture {
                f.requireCompleteCall(report, expected, full = false)
                assertEquals(2, report.originalPeers.rows.size)
                assertEquals(setOf(first.identity, second.identity), report.originalPeers.rows.map { it.root }.toSet())
                assertEquals(2, report.originalPeers.rows.map { it.child }.distinct().size)
                assertTrue(report.originalPeers.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
                f.requireUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun fullWifiPendingPeerParentRefusesBeforeInheritedPhase() = runBlocking {
        val post = withFixture { f ->
            f.createController()
            val held = f.holdBluetoothProcessor()
            f.processors.first().processPacket(RoutedPacket(packet(), PEER_ID, ingressLinkID = "synthetic-link"))
            f.ready(held.entered)
            val entered = CountDownLatch(1)
            val release = f.threadGate()
            f.transport.reusable = false
            val worker = f.worker {
                check(f.holder.getOrCreate { context -> f.createBluetooth(context, beforePeer = {
                    entered.countDown(); f.waitGate(release)
                }) } is HolderAcquisitionResult.Available)
            }
            f.await(entered)
            val hook = AtomicBoolean()
            f.completeFacadeRootOnly()
            f.transport.onDetach = { hook.set(true) }
            f.fence()
            val call = f.launchCollector(full = true)
            val completedBeforeFactoryRelease = call.job.isCompleted
            val noInheritedHook = !hook.get() && worker.isAlive && release.count == 1L
            val earlierPPStillHeld = !held.returned.get() && held.release.count == 1L
            val report = checkNotNull(call.result.get())
            return@withFixture {
                check(noInheritedHook && earlierPPStillHeld)
                assertTrue(completedBeforeFactoryRelease)
                assertEquals(WifiInheritedPhase.NotCollected, report.originalWifiHosts.inheritedPhase)
                assertEquals(WifiHostBatchStatus.Ineligible, report.originalWifiHosts.status)
                assertTrue(WifiHostGap.HolderUnsettled in report.originalWifiHosts.gaps)
                assertTrue(report.originalWifiHosts.joinedHosts.isEmpty() && report.originalPeers.awaitReturnedRoots.isEmpty())
                assertTrue(report.facade is PanicOwnerRetirement.Unconfirmed)
                f.requireUnconfirmed(report)
            }
        }
        post()
    }

    private class Call(val job: Job, val result: AtomicReference<PanicRetirementReport>, val failure: AtomicReference<Throwable>)
    private class Probe(val job: Job, val prepared: AtomicBoolean, val returned: AtomicBoolean, val failure: AtomicReference<Throwable>)
    private class Held(val entered: CompletableDeferred<Unit>, val release: CountDownLatch, val returned: AtomicBoolean)
    /** Metadata only: no returned preparation or authority is restored from this expectation. */
    private class Expected(val processors: List<OriginalProcessorRootIdentity>, val stores: List<OriginalStoreForwardRootIdentity>,
        val peers: List<OriginalPeerRootIdentity>, val peerRows: List<HolderPeerRow>, val hosts: List<WifiHostIdentity>)
    private class ControllerRecord(val participant: OriginalWifiControllerParticipant)
    private class WifiProduct(val host: OriginalWifiHostOwner, val core: MeshCore?) : InertMesh() {
        var running = false
        override fun startServices() { if (host.startAllowed()) running = true }
        override fun stopServices() = Unit // Inert ordinary port, never Core teardown or terminal proof.
    }
    private class CoreRecord {
        val context = AtomicReference<HolderWifiConstructionContext>()
        val failure = AtomicReference<Throwable>()
        val createdOrder = CopyOnWriteArrayList<String>()
        private val actualPeer = AtomicReference<PeerManager>()
        private val actualStore = AtomicReference<StoreForwardManager>()
        private val actualProcessor = AtomicReference<PacketProcessor>()
        private val actualSetter = AtomicReference<PacketProcessor>()
        private val actualCore = AtomicReference<MeshCore>()
        fun peer() = checkNotNull(actualPeer.get())
        fun store() = checkNotNull(actualStore.get())
        fun processor() = checkNotNull(actualProcessor.get())
        fun installPeer(value: PeerManager) { check(actualPeer.compareAndSet(null, value)); createdOrder += "Peer" }
        fun installStore(value: StoreForwardManager) { check(actualStore.compareAndSet(null, value)); createdOrder += "Store" }
        fun installProcessor(value: PacketProcessor) { check(actualProcessor.compareAndSet(null, value)); createdOrder += "Processor" }
        fun installCore(value: MeshCore) { check(actualCore.compareAndSet(null, value)) }
        fun captureSetter(value: PacketProcessor) { check(actualSetter.compareAndSet(null, value)); check(value === processor()) }
        fun setterMatchesOriginal() = actualProcessor.get() != null && actualSetter.get() === actualProcessor.get()
    }

    private class Fixture(private val registerPeers: Boolean) {
        private val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())
        private val scopeJob = SupervisorJob()
        private val errors = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(dispatcher + dispatcher.scheduler + scopeJob + CoroutineExceptionHandler { _, failure -> errors += failure })
        private val facadeJob = SupervisorJob()
        private val coreJobs = CopyOnWriteArrayList<Job>()
        private val controllerJobs = CopyOnWriteArrayList<Job>()
        private val jobs = CopyOnWriteArrayList<Job>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val gates = CopyOnWriteArrayList<CountDownLatch>()
        private val controllers = CopyOnWriteArrayList<ControllerRecord>()
        private val hosts = CopyOnWriteArrayList<OriginalWifiHostOwner>()
        val peers = CopyOnWriteArrayList<PeerManager>()
        val processors = CopyOnWriteArrayList<PacketProcessor>()
        val stores = CopyOnWriteArrayList<StoreForwardManager>()
        private val ownIDs = CopyOnWriteArrayList<Pair<PeerManager, String>>()
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
        @Volatile private var bluetoothValidation: (BitchatPacket) -> Boolean = { false }

        fun otherHolder() = MeshServiceHolderOwner(MeshServiceHolderPorts<InertMesh, UnifiedMeshService, GossipSyncManager>(
            isReusable = { it.reusable }, stopBluetooth = {}, refreshUnified = {}, startGossip = {}, stopGossip = {}, clearGossip = {},
            installGossipDelegate = { manager, delegate -> manager.delegate = delegate }, broadcast = {}, sendToPeer = { _, _ -> }, log = { _, _ -> }
        ))
        fun initialize() {
            val acquired = holder.getUnifiedOrCreate({ context ->
                bluetoothContext = context
                createBluetooth(context).also { transport = it }
            }) { bluetooth -> UnifiedMeshService(bluetooth, UnifiedMeshPorts(
                CoroutineScope(facadeJob + dispatcher), MutableStateFlow(60_000L to false), { false }, { null }, {}, {}, { true }, {}, { null }
            )) }
            check(acquired is HolderAcquisitionResult.Available)
            facade = acquired.product
            binding = captureHolderPanicBindingWithGossip(holder, facade)
        }
        /** Generic Holder contract order, distinct from the real Core's Peer-before-Store-before-PP. */
        fun createBluetooth(context: HolderConstructionContext, beforePeer: () -> Unit = {},
            peerReturned: (PeerManager) -> Unit = {}): InertMesh {
            val pp = holder.constructBluetoothProcessor(context) { PacketProcessor(MY_ID).also { processors += it } }
            pp.delegate = object : PacketProcessorDelegate {
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
            stores += holder.constructBluetoothStoreForward(context) { StoreForwardManager() }
            val creator = { beforePeer(); PeerManager().also { rememberPeer(it) } }
            val peer = if (registerPeers) holder.constructBluetoothPeer(context, creator) else creator()
            peerReturned(peer)
            return InertMesh()
        }
        private fun rememberPeer(peer: PeerManager) {
            peers += peer // Actual ownership immediately on creator return, before later bookkeeping.
            peer.delegate = quietDelegate()
        }
        private fun quietDelegate() = object : PeerManagerDelegate {
            override fun onPeerListUpdated(peerIDs: List<String>) = Unit
            override fun onPeerRemoved(peerID: String) = Unit
        }
        fun createController(core: CoreRecord? = null): ControllerRecord {
            val value = AtomicReference<WifiProduct>()
            val controllerJob = SupervisorJob().also { controllerJobs += it }
            val owner = WifiAwareControllerOwner<WifiProduct, HolderOrigin>(HolderInitialBinding.Uninitialized,
                WifiAwareControllerPorts(scope = CoroutineScope(controllerJob + dispatcher),
                    contextReady = { true }, prepareStart = { true }, restartSupported = { true },
                    factory = { token, origin -> holder.constructWifi(origin, token) { context ->
                        val host = holder.constructWifiHost(context) { OriginalWifiHostOwner(dispatcher).also { hosts += it } }
                        val actualCore = if (core == null) {
                            val creator = { PeerManager().also { rememberPeer(it) } }
                            if (registerPeers) holder.constructWifiPeer(context, creator) else creator()
                            stores += holder.constructWifiStoreForward(context) { StoreForwardManager() }
                            processors += holder.constructWifiProcessor(context) { PacketProcessor(MY_ID) }
                            null
                        } else {
                            check(core.context.compareAndSet(null, context))
                            constructCoreHere(core, context, null)
                        }
                        val product = WifiProduct(host, actualCore)
                        // Real original Deferred, inert body only: no claim about concurrent shipping
                        // stopTail PP waits, ordinary Core teardown or singleton-wide clearing.
                        check(host.installTerminalStop(host.binding) {})
                        check(value.compareAndSet(null, product))
                        product
                    } },
                    start = { it.startServices() }, isRunning = { it.running }, requestStop = { it.stopServices() },
                    awaitProductRetirement = { false }, published = { _, _ -> }, clearPeers = {}, debugMessage = {}, failure = { errors += it }))
            val participant = owner.originalParticipant(original) { it.host.binding }
            val record = ControllerRecord(participant).also { controllers += it }
            val attach = holder.attachOriginalWifiController(original, participant)
            check(attach is HolderWifiInitializationAdmissionResult.Accepted)
            val admission = owner.admitOriginalInitialization(participant)
            check(admission is ControllerInitializationAdmission.Accepted)
            var outcome = WifiInitializationOutcome.UnknownFailure
            try {
                check(owner.beginOriginalOwner(participant, admission.ticket) is WifiControllerBeginResult.Begun)
                owner.setEnabled(true)
                outcome = WifiInitializationOutcome.Begun
            } finally {
                owner.finishOriginalInitialization(admission.ticket, outcome)
                holder.finishWifiInitialization(attach.ticket, outcome)
            }
            check(value.get() != null)
            return record
        }
        fun newUnknownCore(record: CoreRecord, sentinel: Throwable) {
            val actual = worker {
                try {
                    holder.constructWifi(original, Any()) { context ->
                        check(record.context.compareAndSet(null, context))
                        constructCoreHere(record, context, sentinel)
                    }
                    error("Synthetic setter failure must escape genuine constructor")
                } catch (failure: Throwable) {
                    if (failure === sentinel) record.failure.set(failure) else throw failure
                }
            }
            reap(actual)
        }
        private fun constructCoreHere(record: CoreRecord, context: HolderWifiConstructionContext, setterFailure: Throwable?): MeshCore {
            val installationThread = Thread.currentThread()
            val intercepts = mutableListOf<MockedConstruction<*>>()
            var primary: Throwable? = null
            var result: MeshCore? = null
            val coreJob = SupervisorJob().also { coreJobs += it }
            try {
                intercepts += Mockito.mockConstruction(FragmentManager::class.java)
                intercepts += Mockito.mockConstruction(SecureAuthenticatedPeerStateStore::class.java)
                intercepts += Mockito.mockConstruction(com.bitchat.android.mesh.SecurityManager::class.java)
                intercepts += Mockito.mockConstruction(MessageHandler::class.java) { instance, _ ->
                    doAnswer { call ->
                        record.captureSetter(call.getArgument<PacketProcessor>(0))
                        if (setterFailure != null) throw setterFailure
                        null
                    }.whenever(instance).packetProcessor = any()
                }
                val contextStub = mock<Context>()
                whenever(contextStub.applicationContext).thenReturn(contextStub)
                val actual = MeshCore(context = contextStub,
                    scope = CoroutineScope(coreJob + dispatcher), transport = mock<MeshTransport>(),
                    encryptionService = mock<EncryptionService>(), myPeerID = MY_ID, maxTtl = 0u,
                    sharedGossipManager = mock<GossipSyncManager>(),
                    gossipConfigProvider = object : GossipSyncManager.ConfigProvider {
                        override fun seenCapacity() = 8
                        override fun gcsMaxBytes() = 64
                        override fun gcsTargetFpr() = 0.01
                    }, recordFallbackGossip = { _ -> error("Shared inert manager prevents fallback") },
                    recordPeerManager = { creator ->
                        val make = { creator().also { rememberPeer(it); record.installPeer(it) } }
                        if (registerPeers) holder.constructWifiPeer(context, make) else make()
                    },
                    recordStoreForwardManager = { creator ->
                        holder.constructWifiStoreForward(context) { creator().also { stores += it; record.installStore(it) } }
                    },
                    recordPacketProcessor = { creator ->
                        holder.constructWifiProcessor(context) { creator().also { processors += it; record.installProcessor(it) } }
                    }, hooks = MeshCore.Hooks(), weshLocalOnly = true)
                // Core.setupDelegates replaces the constructor delegate. Override BEFORE any own update.
                record.peer().delegate = quietDelegate()
                check(intercepts.size == 4 && intercepts.all { it.constructed().size == 1 })
                record.installCore(actual)
                result = actual
            } catch (failure: Throwable) { primary = failure }
            finally {
                intercepts.asReversed().forEach { intercept ->
                    try { check(Thread.currentThread() === installationThread); intercept.close() }
                    catch (failure: Throwable) {
                        errors += failure
                        val earlier = primary
                        if (earlier == null) primary = failure else if (failure !== earlier) earlier.addSuppressed(failure)
                    }
                }
            }
            primary?.let { throw it }
            return checkNotNull(result)
        }
        fun seedOwnPeer(peer: PeerManager): String {
            val ordinal = ownPeerSequence.incrementAndGet()
            check(ordinal in 1L..0xffffffffL)
            val id = "f10d3900" + ordinal.toString(16).padStart(8, '0')
            ownIDs += peer to id // Own the ID before the business update can invoke a callback.
            peer.updatePeerInfo(id, "synthetic", ByteArray(0), ByteArray(0), false)
            check(peer.getPeerInfo(id) != null)
            return id
        }
        fun holdNaturalRemoval(peer: PeerManager): Held {
            peer.delegate = quietDelegate()
            val id = seedOwnPeer(peer)
            val actualInfo = checkNotNull(peer.getPeerInfo(id))
            check(!actualInfo.isDirectConnection && actualInfo.id == id)
            actualInfo.lastSeen = 0L // Own synthetic fixture state; production clock/60s loop unchanged.
            check(peer.getPeerInfo(id) === actualInfo && actualInfo.lastSeen == 0L)
            val held = Held(CompletableDeferred(), threadGate(), AtomicBoolean())
            peer.delegate = object : PeerManagerDelegate {
                override fun onPeerListUpdated(peerIDs: List<String>) = Unit
                override fun onPeerRemoved(peerID: String) {
                    if (peerID != id) return
                    try {
                        held.entered.complete(Unit)
                        waitGate(held.release)
                        held.returned.set(true)
                    } catch (failure: Throwable) { errors += failure }
                }
            }
            return held
        }
        fun holdBluetoothProcessor(): Held {
            val held = Held(CompletableDeferred(), threadGate(), AtomicBoolean())
            bluetoothValidation = { held.entered.complete(Unit); waitGate(held.release); held.returned.set(true); false }
            return held
        }
        fun fence() {
            check(PanicResetCoordinator.fenceCapturedPanicOwners(facade, binding, facade) === HolderConstructionCloseOutcome.ClosedForConstruction)
        }
        suspend fun completeFacadeRootOnly() { facadeJob.cancel(); join(facadeJob); check(facadeJob.isCompleted) }
        /** Only earlier actual roots are completed here; the target natural Peer callback stays entered. */
        suspend fun prepareEarlier(heldPeer: PeerManager? = null): List<OriginalPeerRootIdentity> {
            fence()
            check(facade.retireForPanic() && facadeJob.isCompleted)
            processors.forEach { it.shutdown() }
            processors.forEach { awaitProcessor(it) }
            stores.forEach { check(requestOriginalStoreForwardShutdown(it.captureOriginalStoreForwardBinding())) }
            stores.forEach { store -> withTimeout(GUARD_MS) { joinStore(store) } }
            val caller = captureOriginalWifiCaller()
            hosts.forEach { host ->
                val request = host.requestTerminalStop(host.binding, caller)
                check(request is WifiStopRequest.Published)
                check(host.awaitStopBody(request.operation, caller))
                val row = checkNotNull(host.observation(host.binding))
                check(row.handlePublished && row.sameDeferredAwaitReturned && row.phase == WifiStopPhase.Returned)
                val ready = host.prepareHostAwait(host.binding, request.operation, caller)
                check(ready is WifiHostPreparation.Ready)
                check(host.cancelPreparedHost(ready.preparation))
                check(host.awaitPreparedHost(ready.preparation))
                check(root(host).isCompleted)
            }
            controllerJobs.forEach { it.cancel(); join(it); check(it.isCompleted) }
            controllers.forEach { record ->
                val view = record.participant.observe()
                check(view.gaps.isEmpty() && record.participant.validate(view))
            }
            val earlierPeers = peers.filter { it !== heldPeer }
            earlierPeers.forEach { check(requestOriginalPeerShutdown(it.captureOriginalPeerBinding())) }
            earlierPeers.forEach { peer -> withTimeout(GUARD_MS) { joinPeer(peer) } }
            return earlierPeers.map { it.captureOriginalPeerBinding().identity }
        }
        fun root(host: OriginalWifiHostOwner): Job = checkNotNull(host.scope.coroutineContext[Job])
        suspend fun requestAndJoinPeers() {
            peers.forEach { check(requestOriginalPeerShutdown(it.captureOriginalPeerBinding())) }
            peers.forEach { peer -> withTimeout(GUARD_MS) { joinPeer(peer) } }
        }
        private suspend fun joinPeer(peer: PeerManager) {
            val binding = peer.captureOriginalPeerBinding()
            val caller = captureOriginalPeerCaller()
            val prepared = prepareOriginalPeerAwait(binding, caller)
            check(prepared is OriginalPeerPreparation.Ready)
            check(validateOriginalPeerPreparation(prepared.preparation))
            check(awaitOriginalPeer(prepared.preparation))
        }
        private suspend fun joinStore(store: StoreForwardManager) {
            val prepared = prepareOriginalStoreForwardAwait(store.captureOriginalStoreForwardBinding(), captureOriginalStoreForwardCaller())
            check(prepared is OriginalStoreForwardPreparation.Ready)
            check(awaitOriginalStoreForward(prepared.preparation))
        }
        suspend fun awaitProcessor(processor: PacketProcessor) { withTimeout(GUARD_MS) { processor.awaitShutdown() } }
        suspend fun collect(full: Boolean): PanicRetirementReport = if (full)
            collectPanicRetirementWithOriginalWifiProcessorStorePeerUnion(facade, PanicOriginalGossipInput.Installed(binding, facade))
        else collectPanicRetirementWithOriginalProcessorStorePeerUnion(facade, PanicOriginalGossipInput.Installed(binding, facade))
        fun launchCollector(full: Boolean, expectedFailure: Throwable? = null): Call {
            val result = AtomicReference<PanicRetirementReport>()
            val failure = AtomicReference<Throwable>()
            val job = own(scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { result.set(collect(full)) }
                catch (caught: Throwable) { failure.set(caught); if (caught !== expectedFailure) errors += caught }
            })
            return Call(job, result, failure)
        }
        fun launchPeerProbe(peer: PeerManager): Probe {
            val prepared = AtomicBoolean()
            val returned = AtomicBoolean()
            val failure = AtomicReference<Throwable>()
            val job = own(scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val binding = peer.captureOriginalPeerBinding()
                    val caller = captureOriginalPeerCaller()
                    val ready = prepareOriginalPeerAwait(binding, caller)
                    check(ready is OriginalPeerPreparation.Ready && validateOriginalPeerPreparation(ready.preparation))
                    prepared.set(true)
                    check(awaitOriginalPeer(ready.preparation))
                    returned.set(true)
                } catch (caught: Throwable) { failure.set(caught); errors += caught }
            })
            return Probe(job, prepared, returned, failure)
        }
        fun completeExpected(full: Boolean): Expected {
            val captured = captureHolderOriginalProcessorStorePeerUnion(binding, facade)
            check(captured is HolderPeerCapture.Observed)
            val batch = captured.batch
            check(batch.peerGaps.isEmpty() && validateHolderOriginalProcessorStorePeerUnion(binding, facade, batch))
            val pp = batch.processorStoreBatch
            check(pp.processorBatch.gaps.isEmpty() && pp.storeForwardGaps.isEmpty())
            check(validateHolderOriginalProcessorStoreForwardUnion(binding, facade, pp))
            check(batch.peerRoots.size == peers.size && pp.processorBatch.roots.size == processors.size && pp.storeForwardRoots.size == stores.size)
            check(batch.peerRoots.map { it.identity } == peers.map { it.captureOriginalPeerBinding().identity })
            if (full) {
                val wifi = captureHolderOriginalWifiProcessorStorePeerUnion(binding, facade)
                check(wifi is HolderWifiPeerCapture.Observed && validateHolderOriginalWifiProcessorStorePeerUnion(binding, facade, wifi.batch))
                check(wifi.batch.wifiBatch.gaps.isEmpty())
            }
            return Expected(pp.processorBatch.roots.map { it.identity }, pp.storeForwardRoots.map { it.identity },
                batch.peerRoots.map { it.identity }, batch.peerRows, hosts.map { it.binding.identity })
        }
        fun evidence(report: PanicRetirementReport): PanicUnconfirmedEvidence.OriginalProcessorStoreForwardUnion =
            (report.transportCoreRadioAndHandlers as PanicOwnerRetirement.Unconfirmed).evidence as PanicUnconfirmedEvidence.OriginalProcessorStoreForwardUnion
        fun requireCompleteCall(report: PanicRetirementReport, expected: Expected, full: Boolean) {
            check(report.facade === PanicOwnerRetirement.Confirmed)
            val old = evidence(report)
            check(old.processorObservation.status == PanicProcessorBatchStatus.AwaitReturned)
            check(old.storeForwardObservation.status == PanicStoreForwardBatchStatus.AwaitReturned)
            check(old.processorObservation.awaitReturnedRoots == expected.processors && old.storeForwardObservation.awaitReturnedRoots == expected.stores)
            check(old.processorObservation.requests.map { it.root } == expected.processors && old.processorObservation.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
            check(old.storeForwardObservation.requests.map { it.root } == expected.stores && old.storeForwardObservation.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
            check(old.processorObservation.holderGaps.isEmpty() && old.processorObservation.callerGaps.isEmpty())
            check(old.storeForwardObservation.holderGaps.isEmpty() && old.storeForwardObservation.callerGaps.isEmpty())
            val peer = report.originalPeers
            check(peer.rows == expected.peerRows && peer.rows.all { it.state == HolderPeerState.Returned && it.root != null })
            check(peer.holderGaps.isEmpty() && peer.callerGaps.isEmpty())
            check(peer.requests.map { it.root } == expected.peers && peer.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
            when (peer.status) {
                PanicPeerBatchStatus.RequestedNotAwaited -> check(peer.awaitReturnedRoots.isEmpty())
                PanicPeerBatchStatus.AwaitReturned -> check(peer.awaitReturnedRoots == expected.peers)
                else -> error("Earlier ineligibility cannot supply a missing-Peer-await policy boundary")
            }
            if (full) {
                val wifi = report.originalWifiHosts
                check(wifi.status == WifiHostBatchStatus.AwaitReturned && wifi.inheritedPhase == WifiInheritedPhase.Returned && wifi.gaps.isEmpty())
                check(wifi.requests == expected.hosts && wifi.cancelledHosts == expected.hosts && wifi.joinedHosts == expected.hosts)
                check(wifi.rows.map { it.host } == expected.hosts && wifi.rows.all { it.handlePublished && it.sameDeferredAwaitReturned && it.phase == WifiStopPhase.Returned && !it.ordinaryUncertainty })
                check(wifi.stopAwaitReturned == wifi.rows.map { checkNotNull(it.operation) })
            }
            requireUnconfirmed(report)
        }
        fun requireLatePeerOnlyGap(report: PanicRetirementReport, expected: Expected, late: OriginalPeerRootIdentity) {
            check(report.facade === PanicOwnerRetirement.Confirmed)
            val old = evidence(report)
            check(old.processorObservation.requests.map { it.root } == expected.processors && old.processorObservation.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
            check(old.storeForwardObservation.requests.map { it.root } == expected.stores && old.storeForwardObservation.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
            check(old.processorObservation.holderGaps.isEmpty() && old.processorObservation.callerGaps.isEmpty())
            check(old.storeForwardObservation.holderGaps.isEmpty() && old.storeForwardObservation.callerGaps.isEmpty())
            check(old.processorObservation.awaitReturnedRoots.isEmpty() && old.storeForwardObservation.awaitReturnedRoots.isEmpty())
            val peer = report.originalPeers
            check(peer.rows == expected.peerRows && peer.rows.all { it.state == HolderPeerState.Returned })
            check(peer.holderGaps.isEmpty() && peer.callerGaps.isEmpty())
            check(peer.status == PanicPeerBatchStatus.NeedsExplicitRetry && peer.awaitReturnedRoots.isEmpty())
            check(peer.requests.map { it.root } == expected.peers.filter { it !== late } && peer.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
            requireUnconfirmed(report)
        }
        fun requireUnconfirmed(report: PanicRetirementReport) {
            check(!report.confirmed)
            check(report.wifiController is PanicOwnerRetirement.Unconfirmed)
            check(report.holderConstructionAndCallbacks is PanicOwnerRetirement.Unconfirmed)
            check(report.gossipJobsAndWriters is PanicOwnerRetirement.Unconfirmed)
            check(report.transportCoreRadioAndHandlers is PanicOwnerRetirement.Unconfirmed)
            check(report.startupAndRemainingSharedKeyUsers is PanicOwnerRetirement.Unconfirmed)
        }
        fun requestFlags() = Triple(processors.map { originalProcessorShutdownRequested(it.captureOriginalProcessorBinding()) },
            stores.map { originalStoreForwardShutdownRequested(it.captureOriginalStoreForwardBinding()) },
            peers.map { originalPeerShutdownRequested(it.captureOriginalPeerBinding()) })
        fun threadGate() = CountDownLatch(1).also { gates += it }
        fun <T : Job> own(job: T): T = job.also { actual -> if (jobs.none { it === actual }) jobs += actual }
        fun worker(action: () -> Unit): Thread = Thread {
            try { action() } catch (failure: Throwable) { errors += failure }
        }.apply { isDaemon = true; workers += this; start() }
        fun waitGate(gate: CountDownLatch) { while (gate.count != 0L) try { gate.await() } catch (failure: InterruptedException) { errors += failure } }
        fun await(gate: CountDownLatch) { check(gate.await(GUARD_MS, TimeUnit.MILLISECONDS)) }
        fun reap(worker: Thread) {
            check(worker !== Thread.currentThread())
            worker.join(GUARD_MS)
            check(!worker.isAlive)
        }
        suspend fun ready(signal: CompletableDeferred<Unit>) { withTimeout(GUARD_MS) { signal.await() } }
        suspend fun periodicReady(signal: CompletableDeferred<Unit>) { withTimeout(PERIODIC_SETUP_MS) { signal.await() } }
        suspend fun join(job: Job) { withTimeout(GUARD_MS) { job.join() } }
        fun healthy() {
            val snapshot = errors.toList()
            if (snapshot.isEmpty()) return
            val first = AssertionError("Unexpected owned Peer fixture failure", snapshot.first())
            val seen = java.util.IdentityHashMap<Throwable, Unit>(); seen[first.cause!!] = Unit
            snapshot.drop(1).forEach { if (seen.put(it, Unit) == null) first.addSuppressed(it) }
            throw first
        }
        suspend fun close() = withContext(NonCancellable) {
            val failures = mutableListOf<Throwable>()
            suspend fun attempt(action: suspend () -> Unit) { try { action() } catch (failure: Throwable) { failures += failure } }
            suspend fun reap(job: Job) {
                attempt { withTimeout(GUARD_MS) { job.join() } }
                attempt { job.join() } // Same actual handle even after preserved guard failure.
            }
            // ALL gates first; no conditional branch can strand the real periodic callback or creator.
            gates.forEach { gate -> attempt { gate.countDown() } }
            workers.forEach { worker ->
                attempt { worker.join(GUARD_MS); check(!worker.isAlive) }
                while (worker.isAlive) attempt { worker.join() }
            }
            processors.forEach { pp -> attempt { pp.shutdown() } }
            processors.forEach { pp -> attempt { withTimeout(GUARD_MS) { pp.awaitShutdown() } }; attempt { pp.awaitShutdown() } }
            stores.forEach { store -> attempt { check(requestOriginalStoreForwardShutdown(store.captureOriginalStoreForwardBinding())) } }
            stores.forEach { store -> attempt { withTimeout(GUARD_MS) { joinStore(store) } }; attempt { joinStore(store) } }
            peers.forEach { peer -> attempt { check(requestOriginalPeerShutdown(peer.captureOriginalPeerBinding())) } }
            val peerReturns = java.util.IdentityHashMap<PeerManager, Unit>()
            peers.forEach { peer ->
                attempt { withTimeout(GUARD_MS) { joinPeer(peer) } }
                attempt { joinPeer(peer); peerReturns[peer] = Unit }
            }
            // Only actual same-root await return permits detaching/removing that fixture's own IDs.
            peers.forEach { peer -> if (peerReturns.containsKey(peer)) attempt { peer.delegate = null } }
            ownIDs.forEach { (peer, id) -> if (peerReturns.containsKey(peer)) attempt { peer.removePeer(id) }
                else failures += AssertionError("Own ID retained: original Peer await did not return") }
            var caller: WifiHostCaller? = null
            attempt { caller = captureOriginalWifiCaller() }
            hosts.forEach { host -> attempt {
                val current = checkNotNull(caller)
                val stop = host.requestTerminalStop(host.binding, current)
                check(stop is WifiStopRequest.Published)
                check(host.awaitStopBody(stop.operation, current))
            } }
            hosts.forEach { host -> attempt { root(host).cancel() } }
            hosts.forEach { host -> reap(root(host)) }
            controllerJobs.forEach { job -> attempt { job.cancel() }; reap(job) }
            jobs.toList().forEach { reap(it) }
            coreJobs.forEach { job -> attempt { job.cancel() }; reap(job) }
            attempt { scopeJob.cancel() }; reap(scopeJob)
            if (::facade.isInitialized) attempt { check(facade.retireForPanic()) }
            attempt { facadeJob.cancel() }; reap(facadeJob)
            attempt { healthy() }
            val first = failures.firstOrNull()
            if (first != null) {
                val seen = java.util.IdentityHashMap<Throwable, Unit>(); seen[first] = Unit
                failures.drop(1).forEach { if (seen.put(it, Unit) == null) first.addSuppressed(it) }
                throw first
            }
        }
    }

    private open class InertMesh(var reusable: Boolean = true) : MeshService {
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
        private val ownPeerSequence = AtomicLong()
        private const val GUARD_MS = 5_000L
        private const val PERIODIC_SETUP_MS = 90_000L
        private const val MY_ID = "1111111111111111"
        private const val PEER_ID = "2222222222222222"
        private fun packet() = BitchatPacket(version = 1u, type = com.bitchat.android.protocol.MessageType.MESSAGE.value,
            senderID = PEER_ID.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            recipientID = com.bitchat.android.protocol.SpecialRecipients.BROADCAST, timestamp = 1uL,
            payload = byteArrayOf(1), ttl = 0u)
        private suspend fun withFixture(registerPeers: Boolean = true, block: suspend (Fixture) -> (() -> Unit)): () -> Unit {
            val fixture = Fixture(registerPeers)
            var primary: Throwable? = null
            var post: (() -> Unit)? = null
            try { fixture.initialize(); post = block(fixture); fixture.healthy() }
            catch (failure: Throwable) { primary = failure }
            finally {
                try { fixture.close() } catch (cleanup: Throwable) {
                    val earlier = primary
                    if (earlier == null) primary = cleanup else if (cleanup !== earlier) earlier.addSuppressed(cleanup)
                }
            }
            primary?.let { throw it }
            return checkNotNull(post)
        }
    }
}
