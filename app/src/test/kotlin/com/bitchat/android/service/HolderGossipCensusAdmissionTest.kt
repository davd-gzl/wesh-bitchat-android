package com.bitchat.android.service

import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.mesh.UnifiedMeshPorts
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.ui.*
import com.bitchat.android.wifiaware.WifiAwareFactoryResult
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger
import com.bitchat.android.sync.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real original-holder operations and scoped census; no platform construction or manager IO loops. */
@OptIn(ExperimentalCoroutinesApi::class)
class HolderGossipCensusAdmissionTest {
    @Test fun completedOriginalContextRefusesManagerCreatorAfterActualPanicClose() = runTest {
        withFixture { f ->
            check(f.holder.provenanceSnapshot().constructions.any {
                it.context === f.context && it.outcome is HolderConstructionOutcome.Returned
            })
            f.fence()
            val report = f.cancelOnly()
            check(f.facadeJob.isCompleted) { "Actual original facade must retire before the request" }
            val before = f.holder.gossipConstructionSnapshot()
            var calls = 0
            val result = runCatching {
                f.holder.constructGossipManager(HolderGossipParent.Constructed(f.context), HolderGossipSource.BLE_SERVICE) {
                    calls++
                    f.manager().manager
                }
            }
            val observedCalls = calls
            val after = f.holder.gossipConstructionSnapshot()
            f.assertHealthy()
            return@withFixture {
                assertEquals("Completed original context must refuse before creator effects", 0, observedCalls)
                val refusal = result.exceptionOrNull() as HolderContextOperationRefusal
                assertEquals(HolderContextOperationKind.ManagerCreator, refusal.operation)
                assertEquals(HolderContextOperationGap.EndedContextAfterClose, refusal.gap)
                assertEquals(before.size, after.size)
                assertSame(before.single().ticket, after.single().ticket)
                assertMissing(report)
            }
        }
    }

    @Test fun preacceptedDispatchedParentCanFinishOriginalManagerAfterClose() = runTest {
        withFixture { f ->
            val entered = f.gate()
            val release = f.gate()
            val late = AtomicReference<Managed>()
            val parent = f.worker {
                created(f.holder.constructWifi(f.original, Any()) { context ->
                    entered.countDown()
                    await(release, "accepted creator release")
                    val manager = f.child(context, HolderGossipSource.CORE_FALLBACK)
                    f.holder.setGossipManager(HolderManagerProvenance.Constructed(context), manager.manager) { it }
                    late.set(manager)
                    Any()
                })
            }
            await(entered, "actual original constructor entry")
            f.fence()
            val whileHeld = f.collect()
            val heldCensus = known(whileHeld).holderFrontier as HolderGossipCensusCapture.Partial<GossipSyncManager>
            val stillHeld = parent.isAlive
            release.countDown()
            f.reap(parent)
            val completed = f.holder.provenanceSnapshot().wifiConstructions.single()
            val actualLate = checkNotNull(late.get())
            val after = f.collect()
            val sealed = known(after).holderFrontier as HolderGossipCensusCapture.Sealed<GossipSyncManager>
            val retained = f.holder.gossipConstructionSnapshot()
            return@withFixture {
                assertTrue(stillHeld)
                assertTrue(heldCensus.gaps.contains(HolderCensusGap.DispatchedConstruction))
                assertTrue(completed.outcome is HolderConstructionOutcome.Returned)
                assertTrue(sealed.frontier.managers.any { it === actualLate.manager })
                assertEquals(2, retained.size)
                assertTrue(heldCensus.operations.none { it.state == HolderContextOperationState.Unknown })
                assertMissing(after)
            }
        }
    }

    @Test fun admittedEnsureWaiterRetainsOriginalEntryUntilActualReturn() = runTest {
        withFixture { f ->
            val legacyEntered = f.gate()
            val releaseLegacy = f.gate()
            f.transport.reusable = false
            val owner = f.worker {
                acquired(f.holder.getOrCreate { context ->
                    legacyEntered.countDown()
                    await(releaseLegacy, "legacy creator release")
                    f.child(context, HolderGossipSource.BLE_SERVICE)
                    InertMesh(reusable = false)
                })
            }
            await(legacyEntered, "actual legacy creator entry")
            val waiter = AtomicReference<Thread>()
            val wifi = AtomicReference<HolderWifiConstructionContext>()
            created(f.holder.constructWifi(f.original, Any()) { context ->
                wifi.set(context)
                val worker = f.worker {
                    f.holder.ensureBluetoothForWifi(context) { nested ->
                        f.child(nested, HolderGossipSource.BLE_SERVICE)
                        InertMesh()
                    }
                }
                waiter.set(worker)
                awaitBlocked(worker, owner)
                Any()
            })
            check(f.holder.provenanceSnapshot().wifiConstructions.single().outcome is HolderConstructionOutcome.Returned)
            f.fence()
            val pending = f.census() as HolderGossipCensusCapture.Partial<GossipSyncManager>
            val operation = pending.operations.single { it.kind == HolderContextOperationKind.BluetoothEnsure }
            var rejectedCalls = 0
            val rejected = f.holder.getOrCreate { rejectedCalls++; InertMesh() }
            releaseLegacy.countDown()
            f.reap(owner)
            f.reap(checkNotNull(waiter.get()))
            val completed = f.census() as HolderGossipCensusCapture.Sealed<GossipSyncManager>
            val snapshot = f.holder.provenanceSnapshot()
            val record = snapshot.wifiConstructions.single()
            val nested = snapshot.constructions.single { it.kind == HolderConstructionKind.BLUETOOTH && it.entry === record.entry }
            val valid = f.validate(completed.frontier)
            return@withFixture {
                assertEquals(HolderContextOperationState.Admitted, operation.state)
                assertSame(record.entry, operation.entry)
                assertSame(record.entry, nested.entry)
                assertTrue(nested.outcome is HolderConstructionOutcome.Returned)
                assertSame(wifi.get(), operation.context)
                assertSame(HolderAcquisitionResult.RefusedBeforeAcquisition, rejected)
                assertEquals(0, rejectedCalls)
                assertTrue(valid)
                assertTrue(pending.gaps.contains(HolderCensusGap.PendingOperation))
            }
        }
    }

    @Test fun admittedRegistrationRetainsManagerAndActualPortCompletion() = runTest {
        withFixture { f ->
            val entered = f.gate()
            val release = f.gate()
            val target = AtomicReference<Managed>()
            val worker = AtomicReference<Thread>()
            f.onInstall = { manager ->
                if (manager === target.get()?.manager) { entered.countDown(); await(release, "registration port release") }
            }
            created(f.holder.constructWifi(f.original, Any()) { context ->
                val actual = f.child(context, HolderGossipSource.CORE_FALLBACK)
                target.set(actual)
                worker.set(f.worker { f.holder.setGossipManager(HolderManagerProvenance.Constructed(context), actual.manager) { it } })
                await(entered, "actual registration installation")
                Any()
            })
            f.fence()
            val pending = f.census() as HolderGossipCensusCapture.Partial<GossipSyncManager>
            val operation = pending.operations.last { it.kind == HolderContextOperationKind.ManagerRegistration }
            val report = f.cancelOnly()
            val closedWhileHeld = f.closed(checkNotNull(target.get()))
            release.countDown()
            f.reap(checkNotNull(worker.get()))
            f.onInstall = null
            val completed = f.census() as HolderGossipCensusCapture.Sealed<GossipSyncManager>
            val refusedManager = f.manager()
            val refusedProduct = InertMesh()
            val installations = f.installations.get()
            val registrationRefusal = runCatching {
                f.holder.setGossipManager(HolderManagerProvenance.UnattributedExternal, refusedManager.manager) { it }
            }.exceptionOrNull()
            val attachRefusal = runCatching { f.holder.attach(refusedProduct) }.exceptionOrNull()
            val after = f.census() as HolderGossipCensusCapture.Partial<GossipSyncManager>
            val installedAfter = f.installations.get()
            val snapshot = f.holder.provenanceSnapshot()
            return@withFixture {
                assertEquals(HolderContextOperationState.Admitted, operation.state)
                assertTrue(closedWhileHeld)
                val input = pending.retainedInputs.single { it.manager === target.get().manager }
                assertSame(operation.identity, input.operation)
                assertSame(operation.entry, input.entry)
                assertTrue(completed.frontier.managers.any { it === target.get().manager })
                assertTrue(registrationRefusal is HolderContextOperationRefusal)
                assertTrue(attachRefusal is HolderContextOperationRefusal)
                assertEquals(installations, installedAfter)
                assertTrue(after.retainedInputs.any { it.manager === refusedManager.manager && it.state == HolderContextOperationState.DeniedBeforeHelperEffects && it.refusal == HolderContextOperationGap.UnattributedAfterClose })
                assertTrue(after.retainedInputs.any { it.product === refusedProduct && it.state == HolderContextOperationState.DeniedBeforeHelperEffects && it.refusal == HolderContextOperationGap.UnattributedAfterClose })
                assertFalse(snapshot.externalProducts.any { it === refusedProduct })
                assertMissing(report)
            }
        }
    }

    @Test fun legacyRetryKeepsBothOriginalOutcomesAndNeverRetriesTypedRefusal() = runTest {
        withFixture { f ->
            val sentinel = IllegalStateException("Synthetic replacement log error")
            var fail = true
            var creators = 0
            f.transport.reusable = false
            f.expect(sentinel)
            f.onLog = { _, text -> if (text == "Created new BluetoothMeshService (replacement)" && fail) { fail = false; throw sentinel } }
            acquired(f.holder.getOrCreate { context -> creators++; f.child(context, HolderGossipSource.BLE_SERVICE); InertMesh() })
            val outcomes = f.holder.provenanceSnapshot().constructions
            val observedCreators = creators
            val failures = f.errors.toList()
            return@withFixture {
                assertEquals(2, observedCreators)
                assertEquals(3, outcomes.count { it.kind == HolderConstructionKind.BLUETOOTH && it.outcome is HolderConstructionOutcome.Returned })
                assertEquals(1, failures.count { it === sentinel })
            }
        }
        withFixture { f ->
            val foreign = f.foreignContext()
            f.transport.reusable = false
            var creators = 0
            val failure = runCatching { acquired(f.holder.getOrCreate { creators++
                f.holder.constructGossipManager(HolderGossipParent.Constructed(foreign), HolderGossipSource.BLE_SERVICE) { f.manager().manager }
                InertMesh()
            }) }.exceptionOrNull()
            val count = creators
            f.fence()
            val debt = f.census() as HolderGossipCensusCapture.Partial<GossipSyncManager>
            return@withFixture {
                assertTrue(failure is HolderContextOperationRefusal)
                assertEquals(1, count)
                assertTrue(debt.gaps.contains(HolderCensusGap.UnknownConstruction))
                assertTrue(debt.gaps.contains(HolderCensusGap.PropagationUnverified))
            }
        }
        withFixture { f ->
            val foreign = f.foreignContext()
            f.transport.reusable = false
            f.onStopBluetooth = {
                f.holder.constructGossipManager(HolderGossipParent.Constructed(foreign), HolderGossipSource.BLE_SERVICE) { f.manager().manager }
            }
            var creators = 0
            val failure = runCatching { f.holder.getOrCreate { creators++; InertMesh() } }.exceptionOrNull()
            check(failure is HolderContextOperationRefusal)
            f.expect(failure)
            val count = creators
            f.fence()
            val debt = f.census() as HolderGossipCensusCapture.Partial<GossipSyncManager>
            return@withFixture {
                assertEquals(0, count)
                assertTrue(debt.gaps.contains(HolderCensusGap.PropagationUnverified))
            }
        }
        withFixture { f ->
            val foreign = f.foreignContext()
            val wrapped = AtomicReference<Throwable>()
            f.onReuse = {
                val denied = runCatching {
                    f.holder.constructGossipManager(HolderGossipParent.Constructed(foreign), HolderGossipSource.BLE_SERVICE) { f.manager().manager }
                }.exceptionOrNull()
                check(denied is HolderContextOperationRefusal)
                throw IllegalStateException("Synthetic wrapped policy signal", denied).also { wrapped.set(it) }
            }
            var creators = 0
            acquired(f.holder.getOrCreate { creators++; InertMesh() })
            val actualWrapper = checkNotNull(wrapped.get())
            f.expect(actualWrapper)
            f.onReuse = null
            f.fence()
            val debt = f.census() as HolderGossipCensusCapture.Partial<GossipSyncManager>
            val count = creators
            val actualErrors = f.errors.toList()
            return@withFixture {
                assertEquals(1, count)
                assertTrue(actualErrors.any { it === actualWrapper })
                assertTrue(actualWrapper.cause is HolderContextOperationRefusal)
                assertTrue(debt.gaps.contains(HolderCensusGap.PropagationUnverified))
            }
        }
        withFixture { f ->
            val foreign = f.foreignContext()
            var observed: Throwable? = null
            var actualWindow = false
            f.onReuse = {
                f.fence()
                val snapshot = f.holder.provenanceSnapshot()
                actualWindow = snapshot.activeEntryCount == 1 && snapshot.constructions.none { it.outcome === HolderConstructionOutcome.Dispatched }
                observed = runCatching {
                    f.holder.constructGossipManager(HolderGossipParent.Constructed(foreign), HolderGossipSource.BLE_SERVICE) { f.manager().manager }
                }.exceptionOrNull()
                true
            }
            var creators = 0
            val reused = acquired(f.holder.getOrCreate { creators++; InertMesh() })
            f.onReuse = null
            val debt = f.census() as HolderGossipCensusCapture.Partial<GossipSyncManager>
            val failure = observed
            val count = creators
            val window = actualWindow
            return@withFixture {
                assertTrue(window)
                assertTrue(failure is HolderContextOperationRefusal)
                assertSame(f.transport, reused)
                assertEquals(0, count)
                assertTrue(debt.gaps.contains(HolderCensusGap.PropagationUnverified))
            }
        }
    }

    @Test fun unknownExternalAndUnavailableParentRemainUnconfirmed() = runTest {
        withFixture { f ->
            f.transport.reusable = false
            val sentinel = IllegalStateException("Synthetic partial constructor")
            var creators = 0
            f.expect(sentinel)
            f.onInstall = { throw sentinel }
            val failure = runCatching { f.holder.getOrCreate { context ->
                creators++
                val manager = f.child(context, HolderGossipSource.BLE_SERVICE)
                f.holder.setGossipManager(HolderManagerProvenance.Constructed(context), manager.manager) { it }
                throw sentinel
            } }.exceptionOrNull()
            f.onInstall = null
            val opaque = IllegalStateException("Synthetic opaque manager creator")
            val unknown = runCatching {
                f.holder.constructGossipManager(HolderGossipParent.Unattributed, HolderGossipSource.CORE_FALLBACK) { throw opaque }
            }.exceptionOrNull()
            val external = f.manager()
            f.holder.setGossipManager(HolderManagerProvenance.UnattributedExternal, external.manager) { it }
            val attached = InertMesh()
            f.holder.attach(attached)
            f.holder.clear()
            f.fence()
            val report = f.collect()
            val census = known(report).holderFrontier as HolderGossipCensusCapture.Partial<GossipSyncManager>
            val attempts = known(report).attempts.toList()
            val count = creators
            val children = f.holder.gossipConstructionSnapshot()
            return@withFixture {
                assertSame(sentinel, failure)
                assertSame(opaque, unknown)
                assertEquals(2, count)
                assertTrue(census.gaps.contains(HolderCensusGap.UnknownConstruction))
                assertEquals(2, census.operations.count { it.kind == HolderContextOperationKind.ManagerRegistration &&
                    it.state == HolderContextOperationState.Unknown && it.failure == HolderOperationFailureKind.OrdinaryThrow })
                assertTrue(census.gaps.contains(HolderCensusGap.UnknownChild))
                assertTrue(census.gaps.contains(HolderCensusGap.ExternalInput))
                assertTrue(children.any { it.outcome === HolderGossipChildOutcome.Unknown })
                assertEquals(children.size, census.snapshot.children.size)
                children.indices.forEach { assertSame(children[it].ticket, census.snapshot.children[it].ticket) }
                assertTrue(attempts.any { it.manager === external.manager && it is PanicGossipRootCloseAttempt.Closed })
                assertMissing(report)
            }
        }
    }

    @Test fun sealedOriginalFrontierRequiresExactActorProductAndSettledOperations() = runTest {
        withFixture { f ->
            f.fence()
            f.cancelOnly()
            val capture = f.census() as HolderGossipCensusCapture.Sealed<GossipSyncManager>
            val valid = f.validate(capture.frontier)
            val other = f.foreignOwner()
            val otherProduct = acquired(other.getUnifiedOrCreate({ Any() }) { Any() })
            val otherSubject = (other.captureUnifiedPanicSubject(otherProduct) as HolderPanicSubjectCapture.Captured).subject
            val wrongActor = f.holder.captureAdmittedManagerFrontier(otherSubject, f.facade)
            val wrongWitness = other.validateAdmittedManagerFrontier(otherSubject, otherProduct, capture.frontier)
            val wrongProduct = captureHolderAdmittedManagerFrontier(f.binding, Any())
            val missing = captureHolderAdmittedManagerFrontier(captureHolderPanicBinding(f.holder, f.facade), f.facade)
            f.holder.clear()
            val stale = f.validate(capture.frontier)
            val refreshed = f.census()
            return@withFixture {
                assertTrue(valid)
                assertTrue(capture.frontier.managers.single() === f.initial.manager)
                assertTrue(wrongActor is HolderGossipCensusCapture.Invalid)
                assertFalse(wrongWitness)
                assertTrue(wrongProduct is HolderGossipCensusCapture.Invalid)
                assertEquals(HolderCensusGap.MissingCollector, (missing as HolderGossipCensusCapture.Invalid).gap)
                assertFalse(stale)
                assertTrue(refreshed is HolderGossipCensusCapture.Sealed)
            }
        }
        withFixture { f ->
            val unmatched = f.manager()
            f.holder.setGossipManager(HolderManagerProvenance.Constructed(f.context), unmatched.manager) { it }
            f.fence()
            val report = f.cancelOnly()
            val census = f.census() as HolderGossipCensusCapture.Partial<GossipSyncManager>
            val actualClosed = f.closed(unmatched)
            return@withFixture {
                assertTrue(actualClosed)
                assertTrue(census.gaps.contains(HolderCensusGap.UnmatchedConstructedRegistration))
                assertSame(unmatched.manager, census.registrationGaps.single().registration.manager)
                assertEquals(HolderRegistrationMatchGap.NoReturnedChild, census.registrationGaps.single().gap)
                assertTrue(census.retainedInputs.any { it.manager === unmatched.manager && it.assignedRegistration != null })
                assertMissing(report)
            }
        }
    }

    @Test fun lateSealManagerDifferenceRequiresFreshCancellationBeforeRecordedJoin() = runTest {
        withFixture { f ->
            val held = f.held()
            val constructorEntered = f.gate()
            val releaseConstructor = f.gate()
            val late = AtomicReference<Managed>()
            val parent = f.worker {
                created(f.holder.constructWifi(f.original, Any()) { context ->
                    constructorEntered.countDown()
                    await(releaseConstructor, "late original manager publication")
                    late.set(f.child(context, HolderGossipSource.CORE_FALLBACK))
                    Any()
                })
            }
            await(constructorEntered, "actual accepted original constructor")
            val facadeEntered = CompletableDeferred<Unit>()
            val facadeRelease = f.suspendedGate()
            f.own(CoroutineScope(f.facadeJob + f.dispatcher).launch {
                withContext(NonCancellable) { facadeEntered.complete(Unit); facadeRelease.await() }
            })
            runCurrent()
            check(held.entered.isCompleted && facadeEntered.isCompleted)
            f.fence()
            val reportRef = AtomicReference<PanicRetirementReport>()
            val collector = f.collectAsync(reportRef)
            runCurrent()
            check(held.job.isCancelled && !collector.isCompleted && !f.facadeJob.isCompleted)
            releaseConstructor.countDown()
            f.reap(parent)
            facadeRelease.complete(Unit)
            runCurrent()
            f.join(collector)
            val first = checkNotNull(reportRef.get())
            val beforeRelease = held.job.isCancelled && !held.job.isCompleted && !held.release.isCompleted
            val newManagerOpen = !f.closed(checkNotNull(late.get()))
            val result = AtomicReference<PanicRetirementReport>()
            val retry = f.collectAsync(result)
            runCurrent()
            val retryWaiting = !retry.isCompleted && !held.job.isCompleted
            val newlyClosed = f.closed(checkNotNull(late.get()))
            held.release.complete(Unit)
            f.join(retry)
            f.join(held.job)
            val second = checkNotNull(result.get())
            return@withFixture {
                assertTrue(beforeRelease)
                assertTrue(newManagerOpen)
                assertEquals(KnownRecordedBatchGap.NeedsExplicitCancellationRetry,
                    ((known(first).recordedFrontierJoin as KnownRecordedFrontiersJoin.Ineligible).reasons.single() as KnownRecordedJoinRejection.Batch).gap)
                assertTrue(retryWaiting)
                assertTrue(newlyClosed)
                assertTrue(known(second).recordedFrontierJoin is KnownRecordedFrontiersJoin.Attempted)
                assertMissing(first)
                assertMissing(second)
            }
        }
    }

    private class Managed(val manager: GossipSyncManager, val work: GossipWorkOwner)
    private class Held(val job: Job, val entered: CompletableDeferred<Unit>, val release: CompletableDeferred<Unit>)
    private class Fixture(scope: TestScope) {
        val facadeJob = SupervisorJob()
        private val gossipJob = SupervisorJob()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val jobs = CopyOnWriteArrayList<Job>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val gates = CopyOnWriteArrayList<CountDownLatch>()
        private val suspendedGates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        private val managers = CopyOnWriteArrayList<Managed>()
        val errors = CopyOnWriteArrayList<Throwable>()
        private val expected = CopyOnWriteArrayList<Throwable>()
        private val handler = CoroutineExceptionHandler { _, failure -> errors += failure }
        private val gossipScope = CoroutineScope(gossipJob + dispatcher + handler)
        val transport = InertMesh()
        val installations = AtomicInteger()
        @Volatile var onInstall: ((GossipSyncManager) -> Unit)? = null
        @Volatile var onStopBluetooth: (() -> Unit)? = null
        @Volatile var onReuse: ((InertMesh) -> Boolean)? = null
        @Volatile var onLog: ((HolderLogLevel, String) -> Unit)? = null
        val holder = MeshServiceHolderOwner(MeshServiceHolderPorts<InertMesh, Any, GossipSyncManager>(
            isReusable = { port { onReuse?.invoke(it) ?: it.reusable } },
            stopBluetooth = { port { onStopBluetooth?.invoke(); Unit } }, refreshUnified = {},
            startGossip = {}, stopGossip = {}, clearGossip = {},
            installGossipDelegate = { manager, delegate -> port {
                manager.delegate = delegate; installations.incrementAndGet(); onInstall?.invoke(manager); Unit
            } }, broadcast = {}, sendToPeer = { _, _ -> }, log = { level, message -> port { onLog?.invoke(level, message); Unit } }
        ))
        val original = holder.initialProcessPreparation().origin
        lateinit var context: HolderConstructionContext
            private set
        lateinit var initial: Managed
            private set
        val facade = acquired(holder.getUnifiedOrCreate({ actual ->
            context = actual
            initial = child(actual, HolderGossipSource.BLE_SERVICE)
            holder.setGossipManager(HolderManagerProvenance.Constructed(actual), initial.manager) { it }
            transport
        }) { bluetooth -> UnifiedMeshService(bluetooth, UnifiedMeshPorts(
            scope = CoroutineScope(facadeJob + dispatcher + handler), announcementProfile = MutableStateFlow(60_000L to false),
            hasAnnouncementRecipients = { false }, currentWifi = { null }, startWifi = {}, stopWifi = {},
            bleEnabled = { true }, disableBle = {}, currentNpub = { null }
        )) }) as UnifiedMeshService
        val binding = captureHolderPanicBindingWithGossip(holder, facade)
        private fun <T> port(action: () -> T): T = try { action() } catch (failure: Throwable) { errors += failure; throw failure }
        fun expect(failure: Throwable) { expected += failure }
        fun manager(): Managed {
            val work = GossipWorkOwner()
            val manager = GossipSyncManager("1122334455667788", gossipScope, object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity() = 8
                override fun gcsMaxBytes() = 128
                override fun gcsTargetFpr() = 0.01
            }, workOwner = work, jobCreator = { _, _, _ -> throw AssertionError("Inert census fixture must not launch manager loops") })
            return Managed(manager, work).also { managers += it }
        }
        fun child(context: HolderConstructionContext, source: HolderGossipSource): Managed {
            lateinit var result: Managed
            val manager = holder.constructGossipManager(HolderGossipParent.Constructed(context), source) { manager().also { result = it }.manager }
            check(manager === result.manager)
            return result
        }
        fun foreignOwner() = MeshServiceHolderOwner(MeshServiceHolderPorts<Any, Any, GossipSyncManager>(
            isReusable = { true }, stopBluetooth = {}, refreshUnified = {}, startGossip = {}, stopGossip = {}, clearGossip = {},
            installGossipDelegate = { _, _ -> }, broadcast = {}, sendToPeer = { _, _ -> }, log = { _, _ -> }
        ))
        fun foreignContext(): HolderConstructionContext {
            val owner = foreignOwner()
            lateinit var context: HolderConstructionContext
            acquired(owner.getOrCreate { context = it; Any() })
            return context
        }
        fun census() = captureHolderAdmittedManagerFrontier(binding, facade)
        fun validate(frontier: HolderAdmittedManagerFrontier<GossipSyncManager>) = validateHolderAdmittedManagerFrontier(binding, facade, frontier)
        fun fence() { check(PanicResetCoordinator.fenceCapturedPanicOwners(facade, binding, facade) === HolderConstructionCloseOutcome.ClosedForConstruction) }
        suspend fun cancelOnly() = withTimeout(WAIT_MS) { collectPanicRetirement(facade, PanicOriginalGossipInput.Installed(binding, facade)) }
        suspend fun collect() = withTimeout(WAIT_MS) { collectPanicRetirementWithRecordedJobsJoin(facade, PanicOriginalGossipInput.Installed(binding, facade)) }
        fun collectAsync(result: AtomicReference<PanicRetirementReport>) = own(CoroutineScope(own(SupervisorJob()) + dispatcher + handler).launch { result.set(collect()) })
        fun closed(managed: Managed): Boolean = when (val root = managed.work.reserveRoot(managed.manager.captureJobPanicBinding(), GossipRootKind.INITIAL_BROADCAST)) {
            is GossipRootReservation.Refused -> true
            is GossipRootReservation.Accepted -> { managed.work.finishRoot(root.lease); false }
        }
        fun held(): Held {
            val entered = CompletableDeferred<Unit>()
            val release = suspendedGate()
            val job = own(launchOriginalGossipJob(gossipScope, dispatcher) {
                launch { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
                awaitCancellation()
            })
            val root = initial.work.reserveRoot(initial.manager.captureJobPanicBinding(), GossipRootKind.INITIAL_BROADCAST) as GossipRootReservation.Accepted
            val ticket = initial.work.reserveChild(root.lease, GossipWorkKind.INITIAL_BROADCAST)
            initial.work.dispatch(ticket, gossipScope, dispatcher, { _, _, _ -> job }) {}
            initial.work.finishRoot(root.lease)
            return Held(job, entered, release)
        }
        fun <T : Job> own(job: T): T = job.also { actual -> if (jobs.none { it === actual }) jobs += actual }
        fun gate() = CountDownLatch(1).also { gates += it }
        fun suspendedGate() = CompletableDeferred<Unit>().also { suspendedGates += it }
        fun worker(action: () -> Unit) = Thread { try { action() } catch (failure: Throwable) { errors += failure } }.apply { isDaemon = true; workers += this; start() }
        fun reap(worker: Thread) { worker.join(WAIT_MS); check(!worker.isAlive) { "Owned worker exceeded completion bound" }; assertHealthy() }
        suspend fun join(job: Job) { withTimeout(WAIT_MS) { job.join() } }
        fun assertHealthy() {
            val unexpected = errors.filter { failure -> expected.none { it === failure } }
            if (unexpected.isNotEmpty()) {
                val failure = AssertionError("Unexpected owned fixture failure")
                failure.initCause(unexpected.first())
                unexpected.drop(1).forEach { if (it !== failure && it !== failure.cause) failure.addSuppressed(it) }
                throw failure
            }
        }
        suspend fun close() = withContext(NonCancellable) {
            var primary: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val original = primary
                    if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
                }
            }
            gates.forEach { gate -> cleanup { gate.countDown() } }
            suspendedGates.forEach { gate -> cleanup { gate.complete(Unit); Unit } }
            workers.forEach { worker ->
                cleanup {
                    var boundedFailure: Throwable? = null
                    try { worker.join(WAIT_MS) } catch (failure: Throwable) { boundedFailure = failure }
                    if (worker.isAlive) {
                        val guard = boundedFailure ?: AssertionError("Owned worker exceeded its first completion bound")
                        try { worker.interrupt(); worker.join(WAIT_MS); check(!worker.isAlive) { "Owned worker must be reaped" } }
                        catch (failure: Throwable) { if (guard !== failure) guard.addSuppressed(failure) }
                        boundedFailure = guard
                    }
                    boundedFailure?.let { throw it }
                }
                while (true) { try { worker.join(); break } catch (failure: Throwable) { cleanup { throw failure } } }
            }
            managers.forEach { managed ->
                cleanup { managed.manager.closeJobRootsForPanic(managed.manager.captureJobPanicBinding()) }
                cleanup { managed.manager.stop() }
                cleanup { managed.manager.delegate = null }
            }
            val allJobs = jobs.toList() + gossipJob + facadeJob
            allJobs.forEach { job -> cleanup { job.cancel() } }
            allJobs.forEach { job ->
                try { withTimeout(WAIT_MS) { job.join() } } catch (failure: Throwable) {
                    val original = primary
                    if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
                }
                cleanup { job.cancel() }
                while (true) { try { job.join(); break } catch (failure: Throwable) { cleanup { throw failure } } }
            }
            cleanup { assertHealthy() }
            primary?.let { throw it }
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> (() -> Unit)) {
        val fixture = Fixture(this)
        var primary: Throwable? = null
        var postconditions: (() -> Unit)? = null
        try { postconditions = block(fixture); fixture.assertHealthy() } catch (failure: Throwable) { primary = failure }
        finally {
            try { fixture.close() } catch (failure: Throwable) {
                val original = primary
                if (original == null) primary = failure else if (original !== failure) original.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
        checkNotNull(postconditions).invoke()
    }

    private class InertMesh(var reusable: Boolean = true) : MeshService {
        var erasures = 0
        override val myPeerID = "1122334455667788"
        override var delegate: MeshDelegate? = null
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
        private const val WAIT_MS = 5_000L
        private fun await(gate: CountDownLatch, reason: String) {
            if (!gate.await(WAIT_MS, TimeUnit.MILLISECONDS)) throw AssertionError("Timed out: $reason")
        }
        private fun awaitBlocked(worker: Thread, owner: Thread) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
            val bean = ManagementFactory.getThreadMXBean()
            while (System.nanoTime() < deadline) {
                val info = bean.getThreadInfo(worker.id)
                if (info?.threadState == Thread.State.BLOCKED && info.lockOwnerId == owner.id) return
                if (!worker.isAlive) throw AssertionError("Owned waiter completed before blocking evidence")
                Thread.yield()
            }
            throw AssertionError("Owned waiter did not reach actual legacy owner")
        }
        private fun <T : Any> acquired(result: HolderAcquisitionResult<T>): T = when (result) {
            is HolderAcquisitionResult.Available -> result.product
            HolderAcquisitionResult.RefusedBeforeAcquisition -> throw AssertionError("Expected accepted original acquisition")
        }
        private fun <T : Any> created(result: WifiAwareFactoryResult<T>): T = when (result) {
            is WifiAwareFactoryResult.Created -> result.value
            WifiAwareFactoryResult.RefusedBeforeAllocation -> throw AssertionError("Expected accepted original constructor")
        }
        private fun known(report: PanicRetirementReport) =
            (report.gossipJobsAndWriters as PanicOwnerRetirement.Unconfirmed).evidence as PanicUnconfirmedEvidence.KnownOriginalGossip
        private fun assertMissing(report: PanicRetirementReport) {
            assertFalse(report.confirmed)
            listOf(report.wifiController, report.holderConstructionAndCallbacks, report.gossipJobsAndWriters,
                report.transportCoreRadioAndHandlers, report.startupAndRemainingSharedKeyUsers).forEach {
                assertEquals(PanicRetirementGap.ProducerNotImplemented, (it as PanicOwnerRetirement.Unconfirmed).gap)
            }
        }
    }
}
