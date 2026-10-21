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

/** Real original Fragment roots: unchanged natural periodic clocks, no ordinary Core/global clear. */
@OptIn(ExperimentalCoroutinesApi::class)
class PanicOriginalFragmentRootsTest {
    @Test fun heldBluetoothOriginalFragmentRootKeepsFullCollectionPending() = runBlocking {
        val post = withFixture(heldSource = HolderFragmentSource.BluetoothService) { f ->
            f.createController()
            f.periodicReady(f.naturalClock.entered) // SETUP only: unchanged real ten-second loop.
            check(f.naturalEntries.get() == 1L)
            val fragment = checkNotNull(f.heldFragment.get())
            f.prepareEarlier()
            val expected = f.completeExpected()
            val call = f.launchCollector()
            val completedBeforeRelease = call.job.isCompleted
            check(originalFragmentShutdownRequested(fragment.captureOriginalFragmentBinding()))
            val probe = f.launchFragmentProbe(fragment)
            val probePending = probe.prepared.get() && !probe.job.isCompleted && !f.naturalClock.returned.get()
            check(f.naturalClock.release.count == 1L)
            return@withFixture {
                f.requireCompleteCall(checkNotNull(call.result.get()), expected)
                check(probePending && probe.returned.get() && probe.failure.get() == null && f.naturalClock.returned.get())
                check(f.fragmentReturns.toSet() == expected.fragments.toSet())
                assertFalse("Full collection must await the held original Bluetooth Fragment root", completedBeforeRelease)
            }
        }
        post()
    }

    @Test fun heldCoreKindOriginalFragmentRootKeepsFullCollectionPending() = runBlocking {
        val post = withFixture(heldSource = HolderFragmentSource.WifiCore) { f ->
            f.createController() // Generic authentic Core-kind parent, not the default MeshCore constructor.
            f.periodicReady(f.naturalClock.entered)
            check(f.naturalEntries.get() == 1L)
            val fragment = checkNotNull(f.heldFragment.get())
            f.prepareEarlier()
            val expected = f.completeExpected()
            val call = f.launchCollector()
            val completedBeforeRelease = call.job.isCompleted
            check(originalFragmentShutdownRequested(fragment.captureOriginalFragmentBinding()))
            val probe = f.launchFragmentProbe(fragment)
            val probePending = probe.prepared.get() && !probe.job.isCompleted && !f.naturalClock.returned.get()
            check(f.naturalClock.release.count == 1L)
            return@withFixture {
                f.requireCompleteCall(checkNotNull(call.result.get()), expected)
                check(probePending && probe.returned.get() && probe.failure.get() == null && f.naturalClock.returned.get())
                check(f.fragmentReturns.toSet() == expected.fragments.toSet())
                assertFalse("Full collection must await the held original Core-kind Fragment root", completedBeforeRelease)
            }
        }
        post()
    }

    @Test fun allKnownSafeFragmentRequestsPrecedeFacadeAndCoordinatorJoins() = runBlocking {
        val post = withFixture { f ->
            f.createController()
            val held = f.holdBluetoothProcessor()
            f.processors.first().processPacket(RoutedPacket(packet(), PEER_ID, ingressLinkID = "synthetic-link"))
            f.ready(held.entered)
            f.completeFacadeRootOnly() // Leave actual delegate retirement unclaimed for the hook.
            val observed = AtomicReference<List<Boolean>>()
            val oldFlags = AtomicReference<Triple<List<Boolean>, List<Boolean>, List<Boolean>>>()
            f.transport.onDetach = {
                observed.set(f.fragments.map { originalFragmentShutdownRequested(it.captureOriginalFragmentBinding()) })
                oldFlags.set(f.requestFlags())
            }
            f.fence()
            val call = f.launchCollector()
            val pendingAtProcessor = !call.job.isCompleted && !held.returned.get() && held.release.count == 1L
            val expected = f.completeExpected()
            return@withFixture {
                check(pendingAtProcessor)
                f.requireCompleteCall(checkNotNull(call.result.get()), expected)
                assertEquals(List(expected.fragments.size) { true }, observed.get())
                val flags = checkNotNull(oldFlags.get())
                assertEquals(List(expected.processors.size) { true }, flags.first)
                assertEquals(List(expected.stores.size) { true }, flags.second)
                assertEquals(List(expected.peers.size) { true }, flags.third)
            }
        }
        post()
    }

    @Test fun realDefaultCoreConstructorRegistersSameOriginalFragment() = runBlocking {
        val post = withFixture { f ->
            val record = CoreRecord()
            val worker = f.worker { f.createController(core = record) }
            f.reap(worker)
            check(record.setterMatchesOriginal() && record.createdOrder == listOf("Peer", "Fragment", "Store", "Processor"))
            check(record.coreFragmentMatchesOriginal())
            f.prepareEarlier()
            f.requestAndJoinFragments()
            val expected = f.completeExpected()
            val report = f.collect()
            return@withFixture {
                f.requireCompleteCall(report, expected)
                assertTrue(record.coreFragmentMatchesOriginal())
                assertTrue(record.fragment().captureOriginalFragmentBinding().identity in f.fragmentReturns)
            }
        }
        post()
    }

    @Test fun foreignWrongKindAndEndedContextsRefuseBeforeFragmentCreator() = runBlocking {
        val post = withFixture { f ->
            val record = CoreRecord()
            val worker = f.worker { f.createController(core = record) }
            f.reap(worker)
            val other = f.otherHolder()
            lateinit var foreign: HolderWifiConstructionContext
            check(other.constructWifi(other.initialProcessPreparation().origin, Any()) { foreign = it; Unit } is WifiAwareFactoryResult.Created)
            f.fence()
            var creators = 0
            fun denied(context: HolderConstructionContext): HolderContextOperationRefusal {
                try { f.holder.constructBluetoothFragment(context) { creators++; error("Refused Fragment creator ran") } }
                catch (failure: HolderContextOperationRefusal) { return failure }
                error("Context must be refused before allocation")
            }
            val foreignFailure = denied(foreign)
            val wrongKind = denied(checkNotNull(record.context.get()))
            val ended = denied(f.bluetoothContext)
            return@withFixture {
                assertEquals(0, creators)
                assertEquals(HolderContextOperationKind.FragmentCreator, foreignFailure.operation)
                assertEquals(HolderContextOperationGap.ForeignContext, foreignFailure.gap)
                assertEquals(HolderContextOperationGap.WrongContextKind, wrongKind.gap)
                assertEquals(HolderContextOperationGap.EndedContextAfterClose, ended.gap)
            }
        }
        post()
    }

    @Test fun completedOriginalFragmentsKeepTruthfulReturnEvidence() = runBlocking {
        val post = withFixture { f ->
            f.createController()
            f.prepareEarlier()
            f.requestAndJoinFragments()
            val expected = f.completeExpected()
            val report = f.collect()
            return@withFixture {
                f.requireCompleteCall(report, expected)
                when (report.originalFragments.status) {
                    PanicFragmentBatchStatus.RequestedNotAwaited -> assertTrue(report.originalFragments.awaitReturnedRoots.isEmpty())
                    PanicFragmentBatchStatus.AwaitReturned -> assertEquals(expected.fragments, report.originalFragments.awaitReturnedRoots)
                    else -> error("Only exact baseline or actual-return remedy evidence is valid")
                }
                f.requireUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun lateFragmentCreatorRetainsFullWifiRefusalUntilExplicitRetry() = runBlocking {
        val post = withFixture { f ->
            f.createController()
            val held = f.holdBluetoothProcessor()
            f.processors.first().processPacket(RoutedPacket(packet(), PEER_ID, ingressLinkID = "synthetic-link"))
            f.ready(held.entered)
            val entered = CountDownLatch(1)
            val release = f.threadGate()
            val returned = AtomicBoolean()
            val late = AtomicReference<FragmentManager>()
            f.transport.reusable = false
            val worker = f.worker {
                check(f.holder.getOrCreate { context -> f.createBluetooth(context,
                    beforeFragment = { entered.countDown(); f.waitGate(release) }, fragmentReturned = { late.set(it) }) } is HolderAcquisitionResult.Available)
                returned.set(true)
            }
            f.await(entered)
            val hook = AtomicBoolean()
            f.completeFacadeRootOnly()
            f.transport.onDetach = { hook.set(true) }
            f.fence()
            val first = f.launchCollector()
            val completedBeforeRelease = first.job.isCompleted
            val firstReport = checkNotNull(first.result.get())
            val refusedBeforeInherited = !hook.get() && worker.isAlive && release.count == 1L && !held.returned.get()
            release.countDown(); f.reap(worker)
            check(returned.get() && late.get() != null)
            val lateWasUnrequested = !originalFragmentShutdownRequested(checkNotNull(late.get()).captureOriginalFragmentBinding())
            held.release.countDown(); f.awaitProcessor(f.processors.first())
            val retry = f.collect() // Explicit request/recapture after true parent return and entry-finally.
            val expected = f.completeExpected()
            return@withFixture {
                check(refusedBeforeInherited && lateWasUnrequested)
                assertTrue(completedBeforeRelease)
                assertEquals(WifiHostBatchStatus.Ineligible, firstReport.originalWifiHosts.status)
                assertEquals(WifiInheritedPhase.NotCollected, firstReport.originalWifiHosts.inheritedPhase)
                assertTrue(WifiHostGap.HolderUnsettled in firstReport.originalWifiHosts.gaps)
                assertTrue(firstReport.originalFragments.awaitReturnedRoots.isEmpty())
                f.requireCompleteCall(retry, expected)
            }
        }
        post()
    }

    @Test fun cancelledAndChangedCallersRetainOriginalFragmentForRetry() = runBlocking {
        val post = withFixture { f ->
            f.fence()
            val sentinel = FixtureCancellation("synthetic Fragment caller cancellation")
            val cancelledParent = Job().also { it.cancel(sentinel); f.own(it) }
            val caught = AtomicReference<Throwable>()
            val result = AtomicReference<PanicRetirementReport>()
            val cancelled = f.own(f.scope.launch(cancelledParent, start = CoroutineStart.UNDISPATCHED) {
                try { result.set(f.collect()) } catch (failure: Throwable) { caught.set(failure) }
            })
            f.join(cancelled)
            val changed = AtomicReference<Boolean>()
            val outer = f.own(f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val binding = f.fragments.first().captureOriginalFragmentBinding()
                val caller = captureOriginalFragmentCaller()
                check(requestOriginalFragmentShutdown(binding))
                val prepared = prepareOriginalFragmentAwait(binding, caller)
                check(prepared is OriginalFragmentPreparation.Ready)
                val inner = f.own(f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    changed.set(awaitOriginalFragment(prepared.preparation))
                })
                inner.join()
            })
            f.join(outer)
            val retry = f.collect()
            val expected = f.completeExpected()
            return@withFixture {
                assertSame(sentinel, caught.get())
                assertNull(result.get())
                assertEquals(false, changed.get())
                f.requireCompleteCall(retry, expected)
            }
        }
        post()
    }

    @Test fun oldPeerScopesDoNotAcquireRequiredFragmentAuthority() = runBlocking {
        val post = withFixture(registerFragments = false) { f ->
            f.createController()
            f.prepareEarlier()
            val lower = f.oldPeer(full = false)
            val full = f.oldPeer(full = true)
            val required = f.collect()
            return@withFixture {
                listOf(lower, full).forEach { report ->
                    assertEquals(PanicPeerBatchStatus.AwaitReturned, report.originalPeers.status)
                    assertEquals(PanicFragmentBatchStatus.NotCollected, report.originalFragments.status)
                    f.requireUnconfirmed(report)
                }
                assertEquals(PanicFragmentBatchStatus.Ineligible, required.originalFragments.status)
                assertTrue(required.originalFragments.awaitReturnedRoots.isEmpty())
            }
        }
        post()
    }

    @Test fun unknownDuplicateAndReplacedFragmentCustodyStaysIneligible() = runBlocking {
        for (mode in listOf("unknown", "duplicate", "replacement")) {
            val post = withFixture { f ->
                val first = f.fragments.first()
                val sentinel = IllegalStateException("synthetic Fragment creator failure")
                if (mode == "unknown") {
                    var caught: Throwable? = null
                    try { f.holder.constructBluetoothFragment(f.bluetoothContext) {
                        FragmentManager(MY_ID).also { f.fragments += it }
                        throw sentinel
                    } } catch (failure: Throwable) { caught = failure }
                    check(caught === sentinel)
                } else if (mode == "duplicate") {
                    check(f.holder.constructBluetoothFragment(f.bluetoothContext) { first } === first)
                } else {
                    // A second distinct real child under the SAME parent cannot replace its first original.
                    f.holder.constructBluetoothFragment(f.bluetoothContext) { FragmentManager(MY_ID).also { f.fragments += it } }
                    check(f.fragments.last() !== first)
                }
                f.fence()
                val captured = captureHolderOriginalProcessorStorePeerFragmentUnion(f.binding, f.facade)
                check(captured is HolderFragmentCapture.Observed)
                val report = f.collect()
                return@withFixture {
                    if (mode == "replacement") {
                        assertEquals(2, captured.batch.fragmentRoots.size)
                        assertEquals(2, captured.batch.fragmentRows.map { it.child }.distinct().size)
                        assertFalse(validateHolderOriginalProcessorStorePeerFragmentUnion(f.binding, InertMesh(), captured.batch))
                        assertTrue(HolderFragmentGap.DuplicateFragment in captured.batch.fragmentGaps)
                        assertTrue(captured.batch.fragmentRoots.any { it === first.captureOriginalFragmentBinding() })
                        assertEquals(PanicFragmentBatchStatus.Ineligible, report.originalFragments.status)
                        assertTrue(report.originalFragments.awaitReturnedRoots.isEmpty())
                        f.requireUnconfirmed(report)
                    } else {
                        val gap = if (mode == "unknown") HolderFragmentGap.UnknownFragment else HolderFragmentGap.DuplicateFragment
                        assertTrue(gap in captured.batch.fragmentGaps)
                        assertEquals(PanicFragmentBatchStatus.Ineligible, report.originalFragments.status)
                        assertTrue(report.originalFragments.awaitReturnedRoots.isEmpty())
                    }
                }
            }
            post()
        }
    }

    @Test fun earlyFixtureFailureStillReapsEveryOriginalFragmentAndWorker() = runBlocking {
        val sentinel = IllegalStateException("synthetic fixture body failure")
        var fixture: Fixture? = null
        var creator: Thread? = null
        var probe: Probe? = null
        var caught: Throwable? = null
        try {
            withFixture(heldSource = HolderFragmentSource.BluetoothService) { f ->
                fixture = f
                f.periodicReady(f.naturalClock.entered)
                check(requestOriginalFragmentShutdown(checkNotNull(f.heldFragment.get()).captureOriginalFragmentBinding()))
                probe = f.launchFragmentProbe(checkNotNull(f.heldFragment.get()))
                check(checkNotNull(probe).prepared.get() && !checkNotNull(probe).job.isCompleted)
                val entered = CountDownLatch(1)
                val release = f.threadGate()
                f.transport.reusable = false
                creator = f.worker {
                    check(f.holder.getOrCreate { context -> f.createBluetooth(context,
                        beforeFragment = { entered.countDown(); f.waitGate(release) }) } is HolderAcquisitionResult.Available)
                }
                f.await(entered)
                throw sentinel
            }
        } catch (failure: Throwable) { caught = failure }
        val actual = checkNotNull(fixture)
        check(checkNotNull(probe).returned.get() && checkNotNull(probe).failure.get() == null)
        check(!checkNotNull(creator).isAlive && actual.naturalClock.returned.get())
        check(actual.fragmentReturns.toSet() == actual.fragments.map { it.captureOriginalFragmentBinding().identity }.toSet())
        assertSame(sentinel, caught)
        assertTrue(sentinel.suppressed.isEmpty())
    }

    private class FixtureCancellation(message: String) : CancellationException(message), CopyableThrowable<FixtureCancellation> {
        override fun createCopy(): FixtureCancellation? = null
    }

    private class Call(val job: Job, val result: AtomicReference<PanicRetirementReport>, val failure: AtomicReference<Throwable>)
    private class Probe(val job: Job, val prepared: AtomicBoolean, val returned: AtomicBoolean, val failure: AtomicReference<Throwable>)
    private class Held(val entered: CompletableDeferred<Unit>, val release: CountDownLatch, val returned: AtomicBoolean)
    /** Metadata only: no returned preparation or authority is restored from this expectation. */
    private class Expected(val processors: List<OriginalProcessorRootIdentity>, val stores: List<OriginalStoreForwardRootIdentity>,
        val peers: List<OriginalPeerRootIdentity>, val peerRows: List<HolderPeerRow>,
        val fragments: List<OriginalFragmentRootIdentity>, val fragmentRows: List<HolderFragmentRow>, val hosts: List<WifiHostIdentity>)
    private class ControllerRecord(val participant: OriginalWifiControllerParticipant)
    private class WifiProduct(val host: OriginalWifiHostOwner, val core: MeshCore?) : InertMesh() {
        var running = false
        override fun startServices() { if (host.startAllowed()) running = true }
        override fun stopServices() = Unit // Inert ordinary port, never Core teardown or terminal proof.
    }
    private class CoreRecord {
        val context = AtomicReference<HolderWifiConstructionContext>()
        val createdOrder = CopyOnWriteArrayList<String>()
        private val actualPeer = AtomicReference<PeerManager>()
        private val actualStore = AtomicReference<StoreForwardManager>()
        private val actualFragment = AtomicReference<FragmentManager>()
        private val actualProcessor = AtomicReference<PacketProcessor>()
        private val actualSetter = AtomicReference<PacketProcessor>()
        private val actualCore = AtomicReference<MeshCore>()
        fun peer() = checkNotNull(actualPeer.get())
        fun store() = checkNotNull(actualStore.get())
        fun fragment() = checkNotNull(actualFragment.get())
        fun processor() = checkNotNull(actualProcessor.get())
        fun installPeer(value: PeerManager) { check(actualPeer.compareAndSet(null, value)); createdOrder += "Peer" }
        fun installFragment(value: FragmentManager) { check(actualFragment.compareAndSet(null, value)); createdOrder += "Fragment" }
        fun installStore(value: StoreForwardManager) { check(actualStore.compareAndSet(null, value)); createdOrder += "Store" }
        fun installProcessor(value: PacketProcessor) { check(actualProcessor.compareAndSet(null, value)); createdOrder += "Processor" }
        fun installCore(value: MeshCore) { check(actualCore.compareAndSet(null, value)) }
        fun coreFragmentMatchesOriginal() = actualFragment.get() != null && actualCore.get()?.fragmentManager === actualFragment.get()
        fun captureSetter(value: PacketProcessor) { check(actualSetter.compareAndSet(null, value)); check(value === processor()) }
        fun setterMatchesOriginal() = actualProcessor.get() != null && actualSetter.get() === actualProcessor.get()
    }

    private class Fixture(private val registerFragments: Boolean, private val heldSource: HolderFragmentSource?) {
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
        val fragments = CopyOnWriteArrayList<FragmentManager>()
        val fragmentReturns = CopyOnWriteArrayList<OriginalFragmentRootIdentity>()
        val naturalClock = Held(CompletableDeferred(), threadGate(), AtomicBoolean())
        val naturalEntries = AtomicLong()
        val heldFragment = AtomicReference<FragmentManager>()
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
            facade.delegate = mock<MeshDelegate>()
            check(transport.delegate === facade)
            binding = captureHolderPanicBindingWithGossip(holder, facade)
        }
        /** Generic Holder contract order, distinct from the real Core's Peer-before-Store-before-PP. */
        fun createBluetooth(context: HolderConstructionContext, beforeFragment: () -> Unit = {},
            fragmentReturned: (FragmentManager) -> Unit = {}): InertMesh {
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
            holder.constructBluetoothPeer(context) { PeerManager().also { rememberPeer(it) } }
            val creator = { beforeFragment(); makeFragment(HolderFragmentSource.BluetoothService) }
            val fragment = if (registerFragments) holder.constructBluetoothFragment(context, creator) else creator()
            fragmentReturned(fragment)
            return InertMesh()
        }
        private fun makeFragment(source: HolderFragmentSource): FragmentManager {
            val selected = heldSource == source && heldFragment.get() == null
            val manager = if (selected) FragmentManager(MY_ID, clock = {
                naturalEntries.incrementAndGet()
                naturalClock.entered.complete(Unit)
                waitGate(naturalClock.release)
                naturalClock.returned.set(true)
                System.currentTimeMillis()
            }) else FragmentManager(MY_ID)
            fragments += manager // Retain the actual manager before any fallible binding/installation.
            if (selected) check(heldFragment.compareAndSet(null, manager))
            return manager
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
                            holder.constructWifiPeer(context) { PeerManager().also { rememberPeer(it) } }
                            val fragmentCreator = { makeFragment(HolderFragmentSource.WifiCore) }
                            if (registerFragments) holder.constructWifiFragment(context, fragmentCreator) else fragmentCreator()
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
        private fun constructCoreHere(record: CoreRecord, context: HolderWifiConstructionContext, setterFailure: Throwable?): MeshCore {
            val installationThread = Thread.currentThread()
            val intercepts = mutableListOf<MockedConstruction<*>>()
            var primary: Throwable? = null
            var result: MeshCore? = null
            val coreJob = SupervisorJob().also { coreJobs += it }
            try {
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
                        holder.constructWifiPeer(context, make)
                    },
                    recordFragmentManager = { creator ->
                        holder.constructWifiFragment(context) { creator().also { fragments += it; record.installFragment(it) } }
                    },
                    recordStoreForwardManager = { creator ->
                        holder.constructWifiStoreForward(context) { creator().also { stores += it; record.installStore(it) } }
                    },
                    recordPacketProcessor = { creator ->
                        holder.constructWifiProcessor(context) { creator().also { processors += it; record.installProcessor(it) } }
                    }, hooks = MeshCore.Hooks(), weshLocalOnly = true)
                // Core.setupDelegates replaces the constructor delegate. Override BEFORE any own update.
                record.peer().delegate = quietDelegate()
                check(intercepts.size == 3 && intercepts.all { it.constructed().size == 1 })
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
        fun holdBluetoothProcessor(): Held {
            val held = Held(CompletableDeferred(), threadGate(), AtomicBoolean())
            bluetoothValidation = { held.entered.complete(Unit); waitGate(held.release); held.returned.set(true); false }
            return held
        }
        fun fence() {
            check(PanicResetCoordinator.fenceCapturedPanicOwners(facade, binding, facade) === HolderConstructionCloseOutcome.ClosedForConstruction)
        }
        suspend fun completeFacadeRootOnly() { facadeJob.cancel(); join(facadeJob); check(facadeJob.isCompleted) }
        /** Complete only inherited actual roots; the selected natural Fragment clock stays entered. */
        suspend fun prepareEarlier(): List<OriginalPeerRootIdentity> {
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
            val earlierPeers = peers.toList()
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
        suspend fun requestAndJoinFragments() {
            fragments.forEach { check(requestOriginalFragmentShutdown(it.captureOriginalFragmentBinding())) }
            fragments.forEach { withTimeout(GUARD_MS) { joinFragment(it) } }
        }
        private suspend fun joinFragment(fragment: FragmentManager) {
            val binding = fragment.captureOriginalFragmentBinding()
            val caller = captureOriginalFragmentCaller()
            val prepared = prepareOriginalFragmentAwait(binding, caller)
            check(prepared is OriginalFragmentPreparation.Ready)
            check(validateOriginalFragmentPreparation(prepared.preparation))
            check(awaitOriginalFragment(prepared.preparation))
        }
        private suspend fun joinStore(store: StoreForwardManager) {
            val prepared = prepareOriginalStoreForwardAwait(store.captureOriginalStoreForwardBinding(), captureOriginalStoreForwardCaller())
            check(prepared is OriginalStoreForwardPreparation.Ready)
            check(awaitOriginalStoreForward(prepared.preparation))
        }
        suspend fun awaitProcessor(processor: PacketProcessor) { withTimeout(GUARD_MS) { processor.awaitShutdown() } }
        suspend fun collect(): PanicRetirementReport =
            collectPanicRetirementWithOriginalWifiProcessorStorePeerFragmentUnion(facade, PanicOriginalGossipInput.Installed(binding, facade))
        suspend fun oldPeer(full: Boolean): PanicRetirementReport = if (full)
            collectPanicRetirementWithOriginalWifiProcessorStorePeerUnion(facade, PanicOriginalGossipInput.Installed(binding, facade))
        else collectPanicRetirementWithOriginalProcessorStorePeerUnion(facade, PanicOriginalGossipInput.Installed(binding, facade))
        fun launchCollector(expectedFailure: Throwable? = null): Call {
            val result = AtomicReference<PanicRetirementReport>()
            val failure = AtomicReference<Throwable>()
            val job = own(scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { result.set(collect()) }
                catch (caught: Throwable) { failure.set(caught); if (caught !== expectedFailure) errors += caught }
            })
            return Call(job, result, failure)
        }
        fun launchFragmentProbe(fragment: FragmentManager): Probe {
            val prepared = AtomicBoolean()
            val returned = AtomicBoolean()
            val failure = AtomicReference<Throwable>()
            val job = own(scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val binding = fragment.captureOriginalFragmentBinding()
                    val caller = captureOriginalFragmentCaller()
                    val ready = prepareOriginalFragmentAwait(binding, caller)
                    check(ready is OriginalFragmentPreparation.Ready && validateOriginalFragmentPreparation(ready.preparation))
                    prepared.set(true)
                    check(awaitOriginalFragment(ready.preparation))
                    returned.set(true)
                } catch (caught: Throwable) { failure.set(caught); errors += caught }
            })
            return Probe(job, prepared, returned, failure)
        }
        fun completeExpected(): Expected {
            val captured = captureHolderOriginalWifiProcessorStorePeerFragmentUnion(binding, facade)
            check(captured is HolderWifiFragmentCapture.Observed)
            check(validateHolderOriginalWifiProcessorStorePeerFragmentUnion(binding, facade, captured.batch))
            val fragmentsBatch = captured.batch.fragmentBatch
            val batch = fragmentsBatch.peerBatch
            val pp = batch.processorStoreBatch
            check(fragmentsBatch.fragmentGaps.isEmpty() && batch.peerGaps.isEmpty() && pp.processorBatch.gaps.isEmpty() && pp.storeForwardGaps.isEmpty())
            check(captured.batch.wifiBatch.gaps.isEmpty())
            check(fragmentsBatch.fragmentRoots.map { it.identity } == fragments.map { it.captureOriginalFragmentBinding().identity })
            check(batch.peerRoots.map { it.identity } == peers.map { it.captureOriginalPeerBinding().identity })
            check(pp.processorBatch.roots.size == processors.size && pp.storeForwardRoots.size == stores.size)
            return Expected(pp.processorBatch.roots.map { it.identity }, pp.storeForwardRoots.map { it.identity },
                batch.peerRoots.map { it.identity }, batch.peerRows, fragmentsBatch.fragmentRoots.map { it.identity },
                fragmentsBatch.fragmentRows, hosts.map { it.binding.identity })
        }
        fun evidence(report: PanicRetirementReport): PanicUnconfirmedEvidence.OriginalProcessorStoreForwardUnion =
            (report.transportCoreRadioAndHandlers as PanicOwnerRetirement.Unconfirmed).evidence as PanicUnconfirmedEvidence.OriginalProcessorStoreForwardUnion
        fun requireCompleteCall(report: PanicRetirementReport, expected: Expected) {
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
            check(peer.status == PanicPeerBatchStatus.AwaitReturned && peer.awaitReturnedRoots == expected.peers)
            val wifi = report.originalWifiHosts
            val wifiStatus = if (expected.hosts.isEmpty()) WifiHostBatchStatus.EmptyValidated else WifiHostBatchStatus.AwaitReturned
            check(wifi.status == wifiStatus && wifi.inheritedPhase == WifiInheritedPhase.Returned && wifi.gaps.isEmpty())
            check(wifi.requests == expected.hosts && wifi.cancelledHosts == expected.hosts && wifi.joinedHosts == expected.hosts)
            check(wifi.rows.map { it.host } == expected.hosts && wifi.rows.all { it.handlePublished && it.sameDeferredAwaitReturned && it.phase == WifiStopPhase.Returned && !it.ordinaryUncertainty })
            check(wifi.stopAwaitReturned == wifi.rows.map { checkNotNull(it.operation) })
            val fragment = report.originalFragments
            check(fragment.rows == expected.fragmentRows && fragment.rows.all { it.state == HolderFragmentState.Returned && it.root != null })
            check(fragment.requests.map { it.root } == expected.fragments && fragment.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
            check(fragment.holderGaps.isEmpty() && fragment.callerGaps.isEmpty())
            when (fragment.status) {
                PanicFragmentBatchStatus.RequestedNotAwaited -> check(fragment.awaitReturnedRoots.isEmpty())
                PanicFragmentBatchStatus.AwaitReturned -> check(fragment.awaitReturnedRoots == expected.fragments)
                else -> error("Earlier ineligibility cannot supply a missing-Fragment-await policy boundary")
            }
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
        fun requestFlags() = Triple(processors.map { it.originalShutdownRequested(it.captureOriginalProcessorBinding()) },
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
            val first = AssertionError("Unexpected owned Fragment fixture failure", snapshot.first())
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
            fragments.forEach { fragment -> attempt { check(requestOriginalFragmentShutdown(fragment.captureOriginalFragmentBinding())) } }
            fragments.forEach { fragment ->
                attempt { withTimeout(GUARD_MS) { joinFragment(fragment) } }
                attempt { joinFragment(fragment); fragmentReturns += fragment.captureOriginalFragmentBinding().identity }
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
        private const val GUARD_MS = 5_000L
        private const val PERIODIC_SETUP_MS = 30_000L
        private const val MY_ID = "1111111111111111"
        private const val PEER_ID = "2222222222222222"
        private fun packet() = BitchatPacket(version = 1u, type = com.bitchat.android.protocol.MessageType.MESSAGE.value,
            senderID = PEER_ID.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            recipientID = com.bitchat.android.protocol.SpecialRecipients.BROADCAST, timestamp = 1uL,
            payload = byteArrayOf(1), ttl = 0u)
        private suspend fun withFixture(registerFragments: Boolean = true, heldSource: HolderFragmentSource? = null, block: suspend (Fixture) -> (() -> Unit)): () -> Unit {
            val fixture = Fixture(registerFragments, heldSource)
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
