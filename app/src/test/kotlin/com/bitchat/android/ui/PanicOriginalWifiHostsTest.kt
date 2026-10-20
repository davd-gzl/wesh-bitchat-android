package com.bitchat.android.ui

import com.bitchat.android.mesh.*
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.service.*
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.wifiaware.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Test

/** Real original hosts/stop Deferreds and required producer roots; no platform product is constructed. */
@OptIn(ExperimentalCoroutinesApi::class)
class PanicOriginalWifiHostsTest {
    @Test fun originalWifiHostWaitsForEnteredNonCancellableChild() = runBlocking {
        val post = withFixture { f ->
            val product = f.createController().product()
            f.prepareCausal()
            val expected = f.completeExpected()
            val held = f.holdHostChild(product)
            f.ready(held.entered)
            val call = f.launchCollector()
            val completedBeforeRelease = call.job.isCompleted
            val rootCancelledBeforeRelease = !f.root(product.host).isActive
            val childStillHeld = !held.returned.get() && held.release.isActive && !f.root(product.host).isCompleted
            f.healthy()
            return@withFixture {
                f.requireCompleteCall(checkNotNull(call.result.get()), expected)
                check(rootCancelledBeforeRelease && childStillHeld)
                assertFalse("The consumer must await the entered child of its original Wi-Fi host", completedBeforeRelease)
            }
        }
        post()
    }

    @Test fun wholeKnownWifiBatchWaitsForSecondOriginalHost() = runBlocking {
        val post = withFixture { f ->
            val first = f.createController().product()
            val second = f.createController().product()
            check(first !== second && first.host !== second.host)
            f.prepareCausal()
            val expected = f.completeExpected()
            val held = f.holdHostChild(second)
            f.ready(held.entered)
            val call = f.launchCollector()
            val completedBeforeRelease = call.job.isCompleted
            val bothCancelledBeforeRelease = !f.root(first.host).isActive && !f.root(second.host).isActive
            val secondStillHeld = !held.returned.get() && held.release.isActive && !f.root(second.host).isCompleted
            f.healthy()
            return@withFixture {
                f.requireCompleteCall(checkNotNull(call.result.get()), expected)
                check(expected.hosts.size == 2 && bothCancelledBeforeRelease && secondStillHeld)
                assertFalse("The whole original batch must await its second entered host child", completedBeforeRelease)
            }
        }
        post()
    }

    @Test fun terminalInitializeAndBeginRefuseSameOriginalWithoutPorts() = runBlocking {
        val post = withFixture { f ->
            val controller = f.createController(start = false)
            val accepted = controller.owner.admitOriginalInitialization(controller.participant)
            check(accepted is ControllerInitializationAdmission.Accepted)
            val before = f.portEffects.get()
            val receipt = controller.participant.fence()
            val refusedAdmission = controller.owner.admitOriginalInitialization(controller.participant)
            val refusedBegin = controller.owner.beginOriginalOwner(controller.participant, accepted.ticket)
            controller.owner.finishOriginalInitialization(accepted.ticket, WifiInitializationOutcome.RefusedByController)
            val effectsAfter = f.portEffects.get()
            return@withFixture {
                check(refusedAdmission is ControllerInitializationAdmission.Refused && refusedBegin is WifiControllerBeginResult.Refused)
                assertSame(receipt, refusedAdmission.receipt)
                assertSame(receipt, refusedBegin.receipt)
                assertEquals(before, effectsAfter)
            }
        }
        post()
    }

    @Test fun stopPublicationPrecedesHeldLegacyPrefix() = runBlocking {
        val post = withFixture { f ->
            val entered = CountDownLatch(1)
            val release = f.threadGate()
            val product = f.createController(body = { entered.countDown(); f.waitGate(release) }).product()
            val request = AtomicReference<WifiStopRequest>()
            val worker = f.worker { runBlocking {
                request.set(product.host.requestTerminalStop(product.host.binding, captureOriginalWifiCaller()))
            } }
            f.await(entered)
            val observed = checkNotNull(product.host.observation(product.host.binding))
            val hostActive = f.root(product.host).isActive
            return@withFixture {
                check(request.get() is WifiStopRequest.Published && !worker.isAlive)
                assertTrue(observed.handlePublished)
                assertEquals(WifiStopPhase.Entered, observed.phase)
                assertFalse(observed.sameDeferredAwaitReturned)
                assertTrue(hostActive)
            }
        }
        post()
    }

    @Test fun preacceptedFactoryPublishesLateHostRequiresExplicitRetry() = runBlocking {
        val pending = withFixture { f ->
            val first = f.createController().product()
            val nested = AtomicReference<Call>()
            val completedInsideFactory = AtomicBoolean()
            val requestedInsideFactory = AtomicReference<List<WifiHostIdentity>>()
            val second = f.createController(start = false, factoryEntry = {
                f.fenceOriginals()
                val call = f.launchCollector()
                nested.set(call)
                completedInsideFactory.set(call.job.isCompleted)
                requestedInsideFactory.set(call.result.get()?.originalWifiHosts?.requests)
            })
            f.startWifi = { second.owner.setEnabled(true) }
            f.facade.startServices()
            val late = second.product()
            val observed = checkNotNull(nested.get()).result.get()
            val lateEffects = late.prefixEffects.get()
            val lateAllowed = late.host.startAllowed()
            val lateOperation = late.host.observation(late.host.binding)?.operation
            return@withFixture {
                check(completedInsideFactory.get() && requestedInsideFactory.get() == listOf(first.host.binding.identity))
                check(observed != null && observed.facade is PanicOwnerRetirement.Unconfirmed)
                assertEquals(WifiInheritedPhase.NotCollected, observed.originalWifiHosts.inheritedPhase)
                assertTrue(observed.originalWifiHosts.status == WifiHostBatchStatus.Ineligible ||
                    observed.originalWifiHosts.status == WifiHostBatchStatus.NeedsExplicitRetry)
                assertFalse(lateAllowed)
                assertEquals(0, lateEffects)
                assertNull(lateOperation)
                f.requireUnconfirmed(observed)
            }
        }
        pending()
        val retry = withFixture { f ->
            f.createController()
            val firstAttempt = AtomicReference<Call>()
            val second = f.createController(start = false, factoryEntry = {
                f.fenceOriginals()
                firstAttempt.set(f.launchCollector())
            })
            f.startWifi = { second.owner.setEnabled(true) }
            f.facade.startServices()
            check(checkNotNull(firstAttempt.get()).job.isCompleted)
            val late = second.product()
            check(!late.host.startAllowed() && late.prefixEffects.get() == 0)
            f.prepareProducersAndFacade()
            val expected = f.completeExpected()
            val call = f.launchCollector()
            f.join(call.job)
            val report = checkNotNull(call.result.get())
            return@withFixture {
                f.requireCompleteCall(report, expected)
                assertEquals(2, expected.hosts.size)
                assertTrue(report.originalWifiHosts.requests.contains(late.host.binding.identity))
            }
        }
        retry()
    }

    @Test fun ordinaryUnitStopOverlapRemainsUnconfirmed() = runBlocking {
        val post = withFixture { f ->
            val held = f.suspendGate()
            val entered = CompletableDeferred<Unit>()
            val controller = f.createController(body = { entered.complete(Unit); held.await() })
            val product = controller.product()
            controller.owner.stop()
            f.ready(entered)
            controller.participant.fence()
            val request = product.host.requestTerminalStop(product.host.binding, captureOriginalWifiCaller())
            val before = product.prefixEffects.get()
            val observed = checkNotNull(product.host.observation(product.host.binding))
            return@withFixture {
                check(request is WifiStopRequest.Unconfirmed)
                assertEquals(WifiHostGap.OrdinaryStopOverlap, request.gap)
                assertEquals(1, before)
                assertTrue(observed.ordinaryUncertainty)
                assertNull(observed.operation)
            }
        }
        post()
    }

    @Test fun cancelledBeforeStopEntryHasNoReturnedBody() = runBlocking {
        val post = withFixture { f ->
            val product = f.createController().product()
            f.root(product.host).cancel()
            f.allowCancelledStop(product)
            val request = product.host.requestTerminalStop(product.host.binding, captureOriginalWifiCaller())
            check(request is WifiStopRequest.Published)
            val observed = checkNotNull(product.host.observation(product.host.binding))
            return@withFixture {
                assertTrue(observed.handlePublished)
                assertEquals(WifiStopPhase.Published, observed.phase)
                assertFalse(observed.sameDeferredAwaitReturned)
                assertEquals(0, product.prefixEffects.get())
            }
        }
        post()
    }

    @Test fun actualStopThrowRetainsSameErrorAndAttemptsOtherProduct() = runBlocking {
        suspend fun observe(failure: Throwable): () -> Unit = withFixture { f ->
            val secondary = StopFailure()
            val first = f.createController(body = { throw failure }).product()
            val second = f.createController(body = { throw secondary }).product()
            f.expectStopFailure(first, failure)
            f.expectStopFailure(second, secondary)
            f.prepareProducersAndFacade()
            val call = f.launchCollector(failure)
            f.join(call.job)
            val caught = call.failure.get()
            val firstRow = checkNotNull(first.host.observation(first.host.binding))
            val secondRow = checkNotNull(second.host.observation(second.host.binding))
            val secondEffects = second.prefixEffects.get()
            return@withFixture {
                assertSame(failure, caught)
                checkNotNull(caught)
                assertEquals(1, caught.suppressed.count { it === secondary })
                assertNull(call.result.get())
                assertEquals(WifiStopPhase.Threw, firstRow.phase)
                assertTrue(firstRow.handlePublished && secondRow.handlePublished)
                assertEquals(WifiStopPhase.Threw, secondRow.phase)
                assertEquals(1, secondEffects)
                assertFalse(firstRow.sameDeferredAwaitReturned)
            }
        }
        observe(StopFailure())()
        observe(SentinelCancellation())()
        val handlerPost = withFixture { f ->
            val first = f.createController().product()
            val second = f.createController().product()
            f.prepareCausal()
            val cancellation = SentinelCancellation()
            val ordinary = StopFailure()
            val handlerEntered = AtomicBoolean()
            val handler = f.cancelCollectorParentOnHostCompletion(first, cancellation, ordinary, handlerEntered)
            try {
                val call = f.launchCollector(cancellation)
                f.join(call.job)
                val caught = call.failure.get()
                val enteredBeforeCleanup = handlerEntered.get()
                val suppressed = caught?.suppressed?.toList().orEmpty()
                val actualHandlerFailure = suppressed.singleOrNull { it.cause === ordinary }
                val firstCancelled = !f.root(first.host).isActive && f.root(first.host).isCompleted
                val secondCancelled = !f.root(second.host).isActive && f.root(second.host).isCompleted
                val firstRow = checkNotNull(first.host.observation(first.host.binding))
                val secondRow = checkNotNull(second.host.observation(second.host.binding))
                val firstEffects = first.prefixEffects.get()
                val secondEffects = second.prefixEffects.get()
                return@withFixture {
                    assertSame(cancellation, caught)
                    assertTrue(enteredBeforeCleanup)
                    assertNull(call.result.get())
                    checkNotNull(actualHandlerFailure)
                    assertSame(ordinary, actualHandlerFailure.cause)
                    assertTrue(actualHandlerFailure !== cancellation && actualHandlerFailure !== ordinary)
                    assertEquals(1, suppressed.count { it === actualHandlerFailure })
                    assertEquals(1, suppressed.count { it.cause === ordinary })
                    assertTrue(firstCancelled && secondCancelled)
                    assertEquals(1, firstEffects)
                    assertEquals(1, secondEffects)
                    assertTrue(firstRow.handlePublished && secondRow.handlePublished)
                    assertEquals(WifiStopPhase.Returned, firstRow.phase)
                    assertEquals(WifiStopPhase.Returned, secondRow.phase)
                    assertTrue(firstRow.sameDeferredAwaitReturned && secondRow.sameDeferredAwaitReturned)
                }
            } finally {
                handler.dispose()
            }
        }
        handlerPost()
    }

    @Test fun foreignBindingAndUninitializedCaptureHaveNoEffects() = runBlocking {
        val post = withFixture { f ->
            val product = f.createController().product()
            val foreign = f.unregisteredHost()
            val request = product.host.requestTerminalStop(foreign.binding, captureOriginalWifiCaller())
            val genericMissing = captureHolderPanicBinding(f.holder, f.facade)
            val report = collectPanicRetirementWithOriginalWifiHosts(f.facade,
                PanicOriginalGossipInput.Installed(genericMissing, f.facade))
            val effects = product.prefixEffects.get()
            val active = f.root(product.host).isActive
            return@withFixture {
                check(request is WifiStopRequest.Unconfirmed)
                assertEquals(WifiHostGap.ForeignBinding, request.gap)
                assertEquals(WifiHostBatchStatus.Ineligible, report.originalWifiHosts.status)
                assertTrue(report.originalWifiHosts.requests.isEmpty())
                assertEquals(0, effects)
                assertTrue(active)
                f.requireUnconfirmed(report)
            }
        }
        post()
    }

    @Test fun externalCallerCancellationRetainsRetryAndSameCustody() = runBlocking {
        val first = withFixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = f.suspendGate()
            val product = f.createController(body = { entered.complete(Unit); release.await() }).product()
            f.prepareProducersAndFacade()
            val cancellation = SentinelCancellation()
            val call = f.launchCollector(cancellation)
            f.ready(entered)
            check(!call.job.isCompleted)
            val observed = checkNotNull(product.host.observation(product.host.binding))
            call.job.cancel(cancellation)
            f.join(call.job)
            val failure = call.failure.get()
            val originalStillActive = f.root(product.host).isActive
            return@withFixture {
                assertSame(cancellation, failure)
                assertNull(call.result.get())
                assertEquals(WifiStopPhase.Entered, observed.phase)
                assertFalse(observed.sameDeferredAwaitReturned)
                assertTrue(originalStillActive)
                assertTrue(observed.operation != null)
            }
        }
        first()
        val related = withFixture { f ->
            val product = f.createController().product()
            val result = AtomicReference<WifiStopRequest>()
            val child = f.own(product.host.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                result.set(product.host.requestTerminalStop(product.host.binding, captureOriginalWifiCaller()))
            })
            f.join(child)
            val effects = product.prefixEffects.get()
            return@withFixture {
                check(result.get() is WifiStopRequest.Unconfirmed)
                assertEquals(WifiHostGap.CallerRelated, (result.get() as WifiStopRequest.Unconfirmed).gap)
                assertEquals(0, effects)
            }
        }
        related()
    }

    private class SentinelCancellation : CancellationException("Synthetic stop cancellation")
    private class StopFailure(val identity: Any = Any()) : RuntimeException("Synthetic stop failure")
    private class Call(val job: Job, val result: AtomicReference<PanicRetirementReport>, val failure: AtomicReference<Throwable>)
    private class Held(val entered: CompletableDeferred<Unit>, val release: CompletableDeferred<Unit>, val returned: AtomicBoolean)
    private class Expected(val processors: List<OriginalProcessorRootIdentity>, val stores: List<OriginalStoreForwardRootIdentity>,
        val hosts: List<WifiHostIdentity>)
    private class ControllerRecord(val owner: WifiAwareControllerOwner<WifiProduct, HolderOrigin>,
        val participant: OriginalWifiControllerParticipant, val productValue: AtomicReference<WifiProduct>) {
        fun product(): WifiProduct = checkNotNull(productValue.get())
    }
    private class WifiProduct(val host: OriginalWifiHostOwner, val ordinary: suspend () -> Unit,
        val own: (Job) -> Job) : InertMesh() {
        val prefixEffects = AtomicInteger()
        var running = false
        override fun startServices() { if (host.startAllowed()) running = true }
        override fun stopServices() {
            if (!host.beginOrdinaryStop()) return
            prefixEffects.incrementAndGet()
            host.retainOrdinaryJob(own(host.scope.launch { ordinary() }))
        }
    }
    private class Fixture {
        private val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())
        private val mainScope = TestScope(dispatcher)
        private val jobs = CopyOnWriteArrayList<Job>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val gates = CopyOnWriteArrayList<CountDownLatch>()
        private val suspendGates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        private val errors = CopyOnWriteArrayList<Throwable>()
        private val controllers = CopyOnWriteArrayList<ControllerRecord>()
        private val hosts = CopyOnWriteArrayList<OriginalWifiHostOwner>()
        private val products = CopyOnWriteArrayList<WifiProduct>()
        private val processors = CopyOnWriteArrayList<PacketProcessor>()
        private val stores = CopyOnWriteArrayList<StoreForwardManager>()
        private val expectedStopFailures = java.util.IdentityHashMap<OriginalWifiHostOwner, Throwable>()
        private val cancelledStops = java.util.IdentityHashMap<OriginalWifiHostOwner, Unit>()
        private val facadeJob = SupervisorJob()
        val portEffects = AtomicInteger()
        val holder = MeshServiceHolderOwner(MeshServiceHolderPorts<InertMesh, UnifiedMeshService, GossipSyncManager>(
            isReusable = { true }, stopBluetooth = {}, refreshUnified = {}, startGossip = {}, stopGossip = {}, clearGossip = {},
            installGossipDelegate = { manager, delegate -> manager.delegate = delegate }, broadcast = {}, sendToPeer = { _, _ -> }, log = { _, _ -> }
        ))
        val original = holder.initialProcessPreparation().origin
        lateinit var facade: UnifiedMeshService
        lateinit var binding: HolderPanicBinding
        private val transport = InertMesh()
        var startWifi: () -> Unit = {}
        fun initialize() {
            val acquired = holder.getUnifiedOrCreate({ context ->
                processors += holder.constructBluetoothProcessor(context) { PacketProcessor("1111111111111111") }
                stores += holder.constructBluetoothStoreForward(context) { StoreForwardManager() }
                transport
            }) { bluetooth -> UnifiedMeshService(bluetooth, UnifiedMeshPorts(
                CoroutineScope(facadeJob + dispatcher), MutableStateFlow(60_000L to false), { false }, { null },
                { startWifi() }, {}, { true }, {}, { null }
            )) }
            check(acquired is HolderAcquisitionResult.Available)
            facade = acquired.product
            binding = captureHolderPanicBindingWithGossip(holder, facade)
        }
        fun createController(body: suspend () -> Unit = {}, start: Boolean = true,
            factoryEntry: () -> Unit = {}): ControllerRecord {
            val productValue = AtomicReference<WifiProduct>()
            val controllerJob = SupervisorJob()
            own(controllerJob)
            controllerRoots += controllerJob
            val owner = WifiAwareControllerOwner<WifiProduct, HolderOrigin>(HolderInitialBinding.Uninitialized,
                WifiAwareControllerPorts(scope = CoroutineScope(controllerJob + dispatcher),
                    contextReady = { true }, prepareStart = { true }, restartSupported = { true },
                    factory = { token, origin -> holder.constructWifi(origin, token) { context ->
                        factoryEntry()
                        val host = holder.constructWifiHost(context) { OriginalWifiHostOwner(dispatcher).also { hosts += it } }
                        processors += holder.constructWifiProcessor(context) { PacketProcessor("1111111111111111") }
                        stores += holder.constructWifiStoreForward(context) { StoreForwardManager() }
                        val product = WifiProduct(host, body, ::own)
                        check(host.installTerminalStop(host.binding) { product.prefixEffects.incrementAndGet(); body() })
                        products += product
                        check(productValue.compareAndSet(null, product))
                        product
                    } },
                    start = { portEffects.incrementAndGet(); it.startServices() }, isRunning = { it.running },
                    requestStop = { portEffects.incrementAndGet(); it.stopServices() }, awaitProductRetirement = { false },
                    published = { _, _ -> portEffects.incrementAndGet() }, clearPeers = { portEffects.incrementAndGet() },
                    debugMessage = {}, failure = { errors += it }))
            val participant = owner.originalParticipant(original) { it.host.binding }
            val attached = holder.attachOriginalWifiController(original, participant)
            check(attached is HolderWifiInitializationAdmissionResult.Accepted)
            val admitted = owner.admitOriginalInitialization(participant)
            check(admitted is ControllerInitializationAdmission.Accepted)
            var outcome = WifiInitializationOutcome.UnknownFailure
            try {
                val begin = owner.beginOriginalOwner(participant, admitted.ticket)
                check(begin is WifiControllerBeginResult.Begun)
                if (start) owner.setEnabled(true)
                outcome = WifiInitializationOutcome.Begun
            } finally {
                owner.finishOriginalInitialization(admitted.ticket, outcome)
                holder.finishWifiInitialization(attached.ticket, outcome)
            }
            return ControllerRecord(owner, participant, productValue).also { controllers += it }
        }
        fun root(host: OriginalWifiHostOwner): Job = checkNotNull(host.scope.coroutineContext[Job])
        fun unregisteredHost(): OriginalWifiHostOwner = OriginalWifiHostOwner(dispatcher).also { host ->
            hosts += host
            check(host.installTerminalStop(host.binding) {})
        }
        fun cancelCollectorParentOnHostCompletion(product: WifiProduct, cancellation: CancellationException,
            ordinary: Throwable, entered: AtomicBoolean): DisposableHandle {
            val actualParent = checkNotNull(mainScope.coroutineContext[Job])
            return root(product.host).invokeOnCompletion {
                entered.set(true)
                actualParent.cancel(cancellation)
                throw ordinary
            }
        }
        fun allowCancelledStop(product: WifiProduct) { cancelledStops[product.host] = Unit }
        fun expectStopFailure(product: WifiProduct, failure: Throwable) { expectedStopFailures[product.host] = failure }
        fun fenceOriginals() {
            check(PanicResetCoordinator.fenceCapturedPanicOwners(facade, binding, facade) === HolderConstructionCloseOutcome.ClosedForConstruction)
        }
        suspend fun prepareProducersAndFacade() {
            fenceOriginals()
            check(facade.retireForPanic() && facadeJob.isCompleted)
            processors.forEach { it.shutdown() }
            processors.forEach { withTimeout(GUARD_MS) { it.awaitShutdown() } }
            stores.forEach { check(requestOriginalStoreForwardShutdown(it.captureOriginalStoreForwardBinding())) }
            stores.forEach { store -> withTimeout(GUARD_MS) { joinStore(store) } }
        }
        suspend fun prepareCausal() {
            prepareProducersAndFacade()
            val caller = captureOriginalWifiCaller()
            products.forEach { product ->
                val operation = product.host.requestTerminalStop(product.host.binding, caller)
                check(operation is WifiStopRequest.Published)
                check(product.host.awaitStopBody(operation.operation, caller))
                val row = checkNotNull(product.host.observation(product.host.binding))
                check(row.phase == WifiStopPhase.Returned && row.handlePublished && row.sameDeferredAwaitReturned)
                check(root(product.host).isActive)
            }
            val capture = captureHolderOriginalWifiHosts(binding, facade)
            check(capture is HolderWifiHostCapture.Observed && capture.batch.gaps.isEmpty())
            check(validateHolderOriginalWifiHosts(binding, facade, capture.batch))
            completeExpected()
        }
        fun completeExpected(): Expected {
            val captured = captureHolderOriginalProcessorStoreForwardUnion(binding, facade)
            check(captured is HolderProcessorStoreForwardCapture.Observed)
            val batch = captured.batch
            check(batch.processorBatch.gaps.isEmpty() && batch.storeForwardGaps.isEmpty())
            check(validateHolderOriginalProcessorStoreForwardUnion(binding, facade, batch))
            check(batch.processorBatch.roots.size == processors.size && batch.storeForwardRoots.size == stores.size)
            return Expected(batch.processorBatch.roots.map { it.identity }, batch.storeForwardRoots.map { it.identity }, products.map { it.host.binding.identity })
        }
        fun holdHostChild(product: WifiProduct): Held {
            val held = Held(CompletableDeferred(), suspendGate(), AtomicBoolean())
            own(product.host.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    held.entered.complete(Unit)
                    held.release.await()
                    held.returned.set(true)
                }
            })
            return held
        }
        fun launchCollector(expected: Throwable? = null): Call {
            val result = AtomicReference<PanicRetirementReport>()
            val failure = AtomicReference<Throwable>()
            val actual = own(mainScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { result.set(collectPanicRetirementWithOriginalWifiHosts(facade, PanicOriginalGossipInput.Installed(binding, facade))) }
                catch (caught: Throwable) { failure.set(caught); if (caught !== expected) errors += caught }
            })
            return Call(actual, result, failure)
        }
        fun requireCompleteCall(report: PanicRetirementReport, expected: Expected) {
            check(report.facade === PanicOwnerRetirement.Confirmed)
            val inherited = (report.transportCoreRadioAndHandlers as PanicOwnerRetirement.Unconfirmed).evidence
                as PanicUnconfirmedEvidence.OriginalProcessorStoreForwardUnion
            check(inherited.processorObservation.status == PanicProcessorBatchStatus.AwaitReturned)
            check(inherited.storeForwardObservation.status == PanicStoreForwardBatchStatus.AwaitReturned)
            check(inherited.processorObservation.awaitReturnedRoots == expected.processors)
            check(inherited.storeForwardObservation.awaitReturnedRoots == expected.stores)
            check(inherited.processorObservation.holderGaps.isEmpty() && inherited.processorObservation.callerGaps.isEmpty())
            check(inherited.storeForwardObservation.holderGaps.isEmpty() && inherited.storeForwardObservation.callerGaps.isEmpty())
            check(inherited.processorObservation.requests.map { it.root } == expected.processors)
            check(inherited.storeForwardObservation.requests.map { it.root } == expected.stores)
            check(inherited.processorObservation.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
            check(inherited.storeForwardObservation.requests.all { it.status == PanicProcessorRequestStatus.RequestReturned })
            val wifi = report.originalWifiHosts
            check(wifi.inheritedPhase == WifiInheritedPhase.Returned && wifi.gaps.isEmpty())
            check(wifi.requests == expected.hosts && wifi.cancelledHosts == expected.hosts)
            check(wifi.rows.map { it.host } == expected.hosts && wifi.rows.all { it.handlePublished && it.phase == WifiStopPhase.Returned && !it.ordinaryUncertainty })
            check(wifi.stopAwaitReturned == wifi.rows.map { checkNotNull(it.operation) })
            when (wifi.status) {
                WifiHostBatchStatus.RequestedNotAwaited -> check(wifi.joinedHosts.isEmpty())
                WifiHostBatchStatus.AwaitReturned -> check(wifi.joinedHosts == expected.hosts)
                else -> error("Earlier ineligibility cannot supply the saved host-join policy boundary")
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
        fun suspendGate() = CompletableDeferred<Unit>().also { suspendGates += it }
        fun threadGate() = CountDownLatch(1).also { gates += it }
        fun <T : Job> own(job: T): T = job.also { value -> if (jobs.none { it === value }) jobs += value }
        fun worker(action: () -> Unit): Thread = Thread {
            try { action() } catch (failure: Throwable) { errors += failure }
        }.apply { isDaemon = true; workers += this; start() }
        fun waitGate(gate: CountDownLatch) {
            if (gate.await(GUARD_MS, TimeUnit.MILLISECONDS)) return
            errors += AssertionError("Owned prefix exceeded its first readiness guard")
            while (gate.count != 0L) try { gate.await() } catch (failure: InterruptedException) { errors += failure }
        }
        fun await(gate: CountDownLatch) { check(gate.await(GUARD_MS, TimeUnit.MILLISECONDS)) }
        suspend fun ready(signal: CompletableDeferred<Unit>) { withTimeout(GUARD_MS) { signal.await() } }
        suspend fun join(job: Job) { withTimeout(GUARD_MS) { job.join() } }
        private suspend fun joinStore(store: StoreForwardManager) {
            val prepared = prepareOriginalStoreForwardAwait(store.captureOriginalStoreForwardBinding(), captureOriginalStoreForwardCaller())
            check(prepared is OriginalStoreForwardPreparation.Ready)
            check(awaitOriginalStoreForward(prepared.preparation))
        }
        fun healthy() {
            val all = errors.toList()
            if (all.isEmpty()) return
            val first = AssertionError("Unexpected owned fixture failure", all.first())
            all.drop(1).forEach { if (it !== first.cause) first.addSuppressed(it) }
            throw first
        }
        suspend fun close() = withContext(NonCancellable) {
            val failures = mutableListOf<Throwable>()
            suspend fun attempt(action: suspend () -> Unit) { try { action() } catch (failure: Throwable) { failures += failure } }
            suspend fun reap(job: Job) {
                attempt { withTimeout(GUARD_MS) { job.join() } }
                attempt { job.join() }
            }
            suspendGates.forEach { gate -> attempt { gate.complete(Unit) } }
            gates.forEach { gate -> attempt { gate.countDown() } }
            workers.forEach { worker ->
                attempt { worker.join(GUARD_MS); check(!worker.isAlive) }
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
            var cleanupCaller: WifiHostCaller? = null
            attempt { cleanupCaller = captureOriginalWifiCaller() }
            hosts.forEach { host ->
                val row = host.observation(host.binding)
                if (row?.operation != null) attempt {
                    val actualCaller = checkNotNull(cleanupCaller)
                    val request = host.requestTerminalStop(host.binding, actualCaller)
                    check(request is WifiStopRequest.Published)
                    try { check(host.awaitStopBody(request.operation, actualCaller)) }
                    catch (failure: Throwable) {
                        if (expectedStopFailures[host] !== failure && !(cancelledStops.containsKey(host) && failure is CancellationException)) throw failure
                    }
                }
            }
            jobs.toList().forEach { job ->
                if (controllerRoots.none { it === job } && job !== mainScope.coroutineContext[Job]) reap(job)
            }
            hosts.forEach { host -> attempt { root(host).cancel() } }
            hosts.forEach { host -> reap(root(host)) }
            controllerRoots.forEach { job -> attempt { job.cancel() }; reap(job) }
            attempt { mainScope.cancel() }
            reap(checkNotNull(mainScope.coroutineContext[Job]))
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
        private val controllerRoots = CopyOnWriteArrayList<Job>()
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
        private suspend fun withFixture(block: suspend (Fixture) -> (() -> Unit)): () -> Unit {
            val fixture = Fixture()
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
