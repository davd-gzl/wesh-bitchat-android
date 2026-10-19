package com.bitchat.android.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

internal typealias GossipJobCreator =
    (CoroutineScope, CoroutineContext, suspend CoroutineScope.() -> Unit) -> Job

internal fun launchOriginalGossipJob(
    scope: CoroutineScope,
    context: CoroutineContext,
    body: suspend CoroutineScope.() -> Unit
): Job = scope.launch(context, block = body)

internal enum class GossipWorkKind { PERIODIC_SYNC, CLEANUP, INITIAL_BROADCAST, INITIAL_PEER }

internal sealed interface GossipManagerJobBinding
internal sealed interface GossipRootLease
internal sealed interface GossipRootCloseReceipt

internal enum class GossipRootKind { START, INITIAL_BROADCAST, INITIAL_PEER }
internal enum class GossipRootState { ACTIVE, FINISHED }
internal class GossipRootIdentity internal constructor()
internal data class GossipRootSnapshot(
    val id: GossipRootIdentity,
    val kind: GossipRootKind,
    val state: GossipRootState
)
internal sealed interface GossipRootReservation {
    data class Accepted(val lease: GossipRootLease) : GossipRootReservation
    data class Refused(val receipt: GossipRootCloseReceipt) : GossipRootReservation
}

internal sealed interface GossipWorkTicket

internal sealed interface GossipWorkOutcome {
    data object Reserved : GossipWorkOutcome
    data object Dispatching : GossipWorkOutcome
    data class Returned(val job: Job) : GossipWorkOutcome
    data object Unknown : GossipWorkOutcome
}

internal data class GossipWorkSnapshot(
    val ticket: GossipWorkTicket,
    val kind: GossipWorkKind,
    val outcome: GossipWorkOutcome
)

/** A finite original observation; neither a sealed frontier nor permission to join. */
internal sealed interface GossipReturnedJobsSnapshot {
    val binding: GossipManagerJobBinding
    val receipt: GossipRootCloseReceipt
    val roots: List<GossipRootSnapshot>
    val records: List<GossipWorkSnapshot>
}
internal sealed interface GossipReturnedJobsCapture {
    class Observed(val snapshot: GossipReturnedJobsSnapshot) : GossipReturnedJobsCapture
    data object ForeignBinding : GossipReturnedJobsCapture
    data object ForeignCloseReceipt : GossipReturnedJobsCapture
}

/** Caller-local preparation; it is never report evidence or new admission authority. */
internal sealed interface GossipRecordedFrontier {
    val originalSnapshot: GossipReturnedJobsSnapshot
}
internal enum class GossipRecordedJoinGap {
    ForeignBinding, ForeignReceipt, ForeignSnapshot, SnapshotMismatch, ActiveRoot,
    ReservedTicket, DispatchingTicket, UnknownTicket, MissingCaller, CallerUninspectable,
    CallerRelated, ForeignPreparation, WrongJoinCaller, OperationFailed
}
internal sealed interface GossipRecordedFrontierPreparation {
    class Ready(val frontier: GossipRecordedFrontier) : GossipRecordedFrontierPreparation
    class Unconfirmed(val gap: GossipRecordedJoinGap) : GossipRecordedFrontierPreparation
}
internal sealed interface GossipRecordedJobsJoined {
    val originalSnapshot: GossipReturnedJobsSnapshot
    val joinedJobs: List<Job>
}
internal sealed interface GossipEmptyFrontierValidation {
    val originalSnapshot: GossipReturnedJobsSnapshot
    val binding: GossipManagerJobBinding
    val receipt: GossipRootCloseReceipt
}
internal sealed interface GossipRecordedFrontierJoinOutcome {
    class Joined(val witness: GossipRecordedJobsJoined) : GossipRecordedFrontierJoinOutcome
    class EmptyFrontierValidated(val witness: GossipEmptyFrontierValidation) : GossipRecordedFrontierJoinOutcome
    class Unconfirmed(val gap: GossipRecordedJoinGap) : GossipRecordedFrontierJoinOutcome
}

internal enum class GossipDirectOperationKind { PublicPacketSeen, RespondRequestSync }
internal sealed interface GossipDirectOperationLease
internal sealed interface GossipDirectCloseReceipt
internal class GossipDirectAssociationIdentity internal constructor()
internal class GossipDirectOperationIdentity internal constructor()
internal class GossipDirectRequestIdentity internal constructor()
internal class GossipDirectCloseIdentity internal constructor()
internal enum class GossipDirectBodyState { Admitted, Returned, Threw }
internal enum class GossipDirectFailureKind { OrdinaryThrow, CancellationThrown, OtherThrowable }
internal enum class GossipDirectSettlement { Recorded, ForeignLease, RepeatedSettlement }
internal enum class GossipDirectDecision { Accepted, Refused }
internal data class GossipDirectOperationRow(
    val identity: GossipDirectOperationIdentity,
    val kind: GossipDirectOperationKind,
    val state: GossipDirectBodyState,
    val failure: GossipDirectFailureKind?
)
internal data class GossipDirectRequestRow(
    val identity: GossipDirectRequestIdentity,
    val kind: GossipDirectOperationKind,
    val decision: GossipDirectDecision,
    val operation: GossipDirectOperationIdentity?,
    val close: GossipDirectCloseIdentity?
)
internal class GossipDirectIngressSnapshot internal constructor(
    val association: GossipDirectAssociationIdentity,
    val close: GossipDirectCloseIdentity?,
    val requests: List<GossipDirectRequestRow>,
    val operations: List<GossipDirectOperationRow>,
    val faults: List<GossipDirectSettlement>
)
internal sealed interface GossipDirectReservation {
    class Accepted(val lease: GossipDirectOperationLease) : GossipDirectReservation
    class Refused(val receipt: GossipDirectCloseReceipt) : GossipDirectReservation
    data object ForeignBinding : GossipDirectReservation
}
internal sealed interface GossipDirectCloseOutcome {
    class Closed(val receipt: GossipDirectCloseReceipt, val snapshotAtClose: GossipDirectIngressSnapshot) : GossipDirectCloseOutcome
    data object ForeignBinding : GossipDirectCloseOutcome
    data object ForeignRootReceipt : GossipDirectCloseOutcome
}
internal sealed interface GossipDirectSnapshotCapture {
    class Observed(val snapshot: GossipDirectIngressSnapshot) : GossipDirectSnapshotCapture
    data object ForeignBinding : GossipDirectSnapshotCapture
}

/** Retains original dispatch outcomes; completion and cancellation do not discharge ownership. */
internal class GossipWorkOwner {
    private class Ticket : GossipWorkTicket
    private class Record(val ticket: GossipWorkTicket, val kind: GossipWorkKind) {
        var outcome: GossipWorkOutcome = GossipWorkOutcome.Reserved
    }

    private class ManagerBinding(val manager: GossipSyncManager) : GossipManagerJobBinding
    private class Lease : GossipRootLease
    private class CloseReceipt : GossipRootCloseReceipt
    private class RootRecord(
        val binding: GossipManagerJobBinding,
        val kind: GossipRootKind
    ) {
        val id = GossipRootIdentity()
        var state = GossipRootState.ACTIVE
        val children = mutableListOf<GossipWorkTicket>()
    }

    private val monitor = Any()
    private val records = LinkedHashMap<GossipWorkTicket, Record>()
    private val roots = LinkedHashMap<GossipRootLease, RootRecord>()
    private var association: ManagerBinding? = null
    private var rootCloseReceipt: GossipRootCloseReceipt? = null

    private class DirectLease : GossipDirectOperationLease
    private class DirectRecord(val kind: GossipDirectOperationKind) {
        val identity = GossipDirectOperationIdentity()
        var state = GossipDirectBodyState.Admitted
        var failure: GossipDirectFailureKind? = null
    }
    private class DirectCloseReceipt(
        val binding: GossipManagerJobBinding,
        val root: GossipRootCloseReceipt,
        val identity: GossipDirectCloseIdentity
    ) : GossipDirectCloseReceipt
    private val directAssociation = GossipDirectAssociationIdentity()
    private val directOperations = LinkedHashMap<DirectLease, DirectRecord>()
    private val directRequests = mutableListOf<GossipDirectRequestRow>()
    private val directFaults = mutableListOf<GossipDirectSettlement>()
    private var directCloseReceipt: DirectCloseReceipt? = null

    /** A separate terminal event: root closure alone never closes fresh direct ingress. */
    fun closeDirectIngress(
        binding: GossipManagerJobBinding,
        originalRootReceipt: GossipRootCloseReceipt
    ): GossipDirectCloseOutcome = synchronized(monitor) {
        if (binding !== association) return@synchronized GossipDirectCloseOutcome.ForeignBinding
        if (rootCloseReceipt == null || originalRootReceipt !== rootCloseReceipt) {
            return@synchronized GossipDirectCloseOutcome.ForeignRootReceipt
        }
        val receipt = directCloseReceipt ?: DirectCloseReceipt(binding, originalRootReceipt,
            GossipDirectCloseIdentity()).also { directCloseReceipt = it }
        GossipDirectCloseOutcome.Closed(receipt, directSnapshotLocked())
    }

    fun reserveDirect(binding: GossipManagerJobBinding, kind: GossipDirectOperationKind): GossipDirectReservation =
        synchronized(monitor) {
            if (binding !== association) return@synchronized GossipDirectReservation.ForeignBinding
            val closed = directCloseReceipt
            if (closed != null) {
                directRequests += GossipDirectRequestRow(GossipDirectRequestIdentity(), kind,
                    GossipDirectDecision.Refused, null, closed.identity)
                return@synchronized GossipDirectReservation.Refused(closed)
            }
            val lease = DirectLease()
            val record = DirectRecord(kind)
            directOperations[lease] = record
            directRequests += GossipDirectRequestRow(GossipDirectRequestIdentity(), kind,
                GossipDirectDecision.Accepted, record.identity, null)
            GossipDirectReservation.Accepted(lease)
        }

    /** A throw can follow partial effects; settlement never retains the original Throwable. */
    fun noteDirectBodyEnd(
        lease: GossipDirectOperationLease,
        failure: GossipDirectFailureKind?
    ): GossipDirectSettlement = synchronized(monitor) {
        val record = if (lease is DirectLease) directOperations[lease] else null
        if (record == null) {
            directFaults += GossipDirectSettlement.ForeignLease
            return@synchronized GossipDirectSettlement.ForeignLease
        }
        if (record.state != GossipDirectBodyState.Admitted) {
            directFaults += GossipDirectSettlement.RepeatedSettlement
            return@synchronized GossipDirectSettlement.RepeatedSettlement
        }
        record.failure = failure
        record.state = if (failure == null) GossipDirectBodyState.Returned else GossipDirectBodyState.Threw
        GossipDirectSettlement.Recorded
    }

    fun directIngressSnapshot(binding: GossipManagerJobBinding): GossipDirectSnapshotCapture = synchronized(monitor) {
        if (binding !== association) GossipDirectSnapshotCapture.ForeignBinding
        else GossipDirectSnapshotCapture.Observed(directSnapshotLocked())
    }

    private fun directSnapshotLocked() = GossipDirectIngressSnapshot(directAssociation, directCloseReceipt?.identity,
        directRequests.toList(), directOperations.values.map {
            GossipDirectOperationRow(it.identity, it.kind, it.state, it.failure)
        }, directFaults.toList())

    fun associateManager(manager: GossipSyncManager): GossipManagerJobBinding {
        check(manager.hasOriginalWorkOwner(this)) { "Manager has a different original work owner" }
        return synchronized(monitor) {
            val original = association
            if (original != null) {
                check(original.manager === manager) { "Work owner already belongs to another manager" }
                original
            } else {
                ManagerBinding(manager).also { association = it }
            }
        }
    }

    fun closeJobRoots(binding: GossipManagerJobBinding): GossipRootCloseReceipt = synchronized(monitor) {
        check(binding === association) { "Job root binding does not belong to this owner" }
        rootCloseReceipt ?: CloseReceipt().also { rootCloseReceipt = it }
    }

    private class ReturnedJobsSnapshot(
        override val binding: GossipManagerJobBinding,
        override val receipt: GossipRootCloseReceipt,
        override val roots: List<GossipRootSnapshot>,
        override val records: List<GossipWorkSnapshot>
    ) : GossipReturnedJobsSnapshot

    /** Validate both original identities before copying rows; never invoke a Job under this monitor. */
    fun captureReturnedJobsForCancellation(
        binding: GossipManagerJobBinding,
        receipt: GossipRootCloseReceipt
    ): GossipReturnedJobsCapture = synchronized(monitor) {
        if (binding !== association) return@synchronized GossipReturnedJobsCapture.ForeignBinding
        if (rootCloseReceipt == null || receipt !== rootCloseReceipt) {
            return@synchronized GossipReturnedJobsCapture.ForeignCloseReceipt
        }
        // Both reentrant copies finish in this one transaction. Accepted work may publish later.
        GossipReturnedJobsCapture.Observed(ReturnedJobsSnapshot(binding, receipt, rootSnapshot(), snapshot()))
    }

    private class PreparedFrontier(
        val anchor: Any,
        override val originalSnapshot: ReturnedJobsSnapshot,
        val caller: Job,
        val jobs: List<Job>
    ) : GossipRecordedFrontier
    private class JoinedRecordedJobs(
        override val originalSnapshot: GossipReturnedJobsSnapshot,
        override val joinedJobs: List<Job>
    ) : GossipRecordedJobsJoined
    private class ValidatedEmptyFrontier(
        override val originalSnapshot: GossipReturnedJobsSnapshot,
        override val binding: GossipManagerJobBinding,
        override val receipt: GossipRootCloseReceipt
    ) : GossipEmptyFrontierValidation
    private val joinAnchor = Any()

    /** No Job getter or operation runs in the atomic recorded-frontier check. */
    private fun recordedSnapshotGapLocked(
        binding: GossipManagerJobBinding,
        receipt: GossipRootCloseReceipt,
        snapshot: GossipReturnedJobsSnapshot
    ): GossipRecordedJoinGap? {
        if (binding !== association) return GossipRecordedJoinGap.ForeignBinding
        if (rootCloseReceipt == null || receipt !== rootCloseReceipt) return GossipRecordedJoinGap.ForeignReceipt
        if (snapshot !is ReturnedJobsSnapshot || snapshot.binding !== binding || snapshot.receipt !== receipt) {
            return GossipRecordedJoinGap.ForeignSnapshot
        }
        if (roots.size != snapshot.roots.size || records.size != snapshot.records.size) {
            return GossipRecordedJoinGap.SnapshotMismatch
        }
        roots.values.zip(snapshot.roots).forEach { (actual, observed) ->
            if (actual.id !== observed.id || actual.kind != observed.kind || actual.state != observed.state) {
                return GossipRecordedJoinGap.SnapshotMismatch
            }
            if (observed.state != GossipRootState.FINISHED) return GossipRecordedJoinGap.ActiveRoot
        }
        records.values.zip(snapshot.records).forEach { (actual, observed) ->
            if (actual.ticket !== observed.ticket || actual.kind != observed.kind) return GossipRecordedJoinGap.SnapshotMismatch
            val returned = when (val outcome = observed.outcome) {
                GossipWorkOutcome.Reserved -> return GossipRecordedJoinGap.ReservedTicket
                GossipWorkOutcome.Dispatching -> return GossipRecordedJoinGap.DispatchingTicket
                GossipWorkOutcome.Unknown -> return GossipRecordedJoinGap.UnknownTicket
                is GossipWorkOutcome.Returned -> outcome
            }
            val current = actual.outcome as? GossipWorkOutcome.Returned
            if (current == null || current.job !== returned.job) return GossipRecordedJoinGap.SnapshotMismatch
        }
        return null
    }

    /** Prepare all known managers before joining any; incomplete old observations are not refreshed. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun prepareRecordedFrontierJoin(
        binding: GossipManagerJobBinding,
        receipt: GossipRootCloseReceipt,
        snapshot: GossipReturnedJobsSnapshot
    ): GossipRecordedFrontierPreparation {
        val original = synchronized(monitor) {
            val gap = recordedSnapshotGapLocked(binding, receipt, snapshot)
            if (gap != null) return GossipRecordedFrontierPreparation.Unconfirmed(gap)
            snapshot as ReturnedJobsSnapshot
        }
        val context = currentCoroutineContext()
        val caller = context[Job] ?: return GossipRecordedFrontierPreparation.Unconfirmed(GossipRecordedJoinGap.MissingCaller)
        context.ensureActive()
        val chain = java.util.IdentityHashMap<Job, Unit>()
        try {
            var current: Job? = caller
            while (current != null) {
                if (chain.containsKey(current)) {
                    return GossipRecordedFrontierPreparation.Unconfirmed(GossipRecordedJoinGap.CallerUninspectable)
                }
                chain[current] = Unit
                current = current.parent
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return GossipRecordedFrontierPreparation.Unconfirmed(GossipRecordedJoinGap.CallerUninspectable)
        }
        context.ensureActive()
        return synchronized(monitor) {
            val gap = recordedSnapshotGapLocked(binding, receipt, original)
            if (gap != null) return@synchronized GossipRecordedFrontierPreparation.Unconfirmed(gap)
            val seen = java.util.IdentityHashMap<Job, Unit>()
            val jobs = original.records.map { (it.outcome as GossipWorkOutcome.Returned).job }.filter {
                if (seen.containsKey(it)) false else { seen[it] = Unit; true }
            }
            if (jobs.any { chain.containsKey(it) }) {
                GossipRecordedFrontierPreparation.Unconfirmed(GossipRecordedJoinGap.CallerRelated)
            } else GossipRecordedFrontierPreparation.Ready(PreparedFrontier(joinAnchor, original, caller, jobs))
        }
    }

    /** Actual inline same-Job joins; the private caller-bearing preparation never enters a witness. */
    suspend fun joinPreparedRecordedFrontier(frontier: GossipRecordedFrontier): GossipRecordedFrontierJoinOutcome {
        if (frontier !is PreparedFrontier || frontier.anchor !== joinAnchor) {
            return GossipRecordedFrontierJoinOutcome.Unconfirmed(GossipRecordedJoinGap.ForeignPreparation)
        }
        val context = currentCoroutineContext()
        if (context[Job] !== frontier.caller) {
            return GossipRecordedFrontierJoinOutcome.Unconfirmed(GossipRecordedJoinGap.WrongJoinCaller)
        }
        context.ensureActive()
        synchronized(monitor) {
            val gap = recordedSnapshotGapLocked(frontier.originalSnapshot.binding, frontier.originalSnapshot.receipt,
                frontier.originalSnapshot)
            if (gap != null) return GossipRecordedFrontierJoinOutcome.Unconfirmed(gap)
        }
        try {
            frontier.jobs.forEach { it.join() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return GossipRecordedFrontierJoinOutcome.Unconfirmed(GossipRecordedJoinGap.OperationFailed)
        }
        context.ensureActive()
        return synchronized(monitor) {
            val original = frontier.originalSnapshot
            val gap = recordedSnapshotGapLocked(original.binding, original.receipt, original)
            if (gap != null) return@synchronized GossipRecordedFrontierJoinOutcome.Unconfirmed(gap)
            if (frontier.jobs.isEmpty()) {
                GossipRecordedFrontierJoinOutcome.EmptyFrontierValidated(
                    ValidatedEmptyFrontier(original, original.binding, original.receipt)
                )
            } else GossipRecordedFrontierJoinOutcome.Joined(JoinedRecordedJobs(original, frontier.jobs.toList()))
        }
    }

    fun reserveRoot(binding: GossipManagerJobBinding, kind: GossipRootKind): GossipRootReservation = synchronized(monitor) {
        check(binding === association) { "Job root binding does not belong to this owner" }
        val closed = rootCloseReceipt
        if (closed != null) return@synchronized GossipRootReservation.Refused(closed)
        val lease = Lease()
        roots[lease] = RootRecord(binding, kind)
        GossipRootReservation.Accepted(lease)
    }

    fun reserveChild(lease: GossipRootLease, kind: GossipWorkKind): GossipWorkTicket = synchronized(monitor) {
        val root = roots[lease]
        check(root != null && root.binding === association && root.state == GossipRootState.ACTIVE) {
            "Job root lease is foreign or finished"
        }
        val expected = when (root.kind) {
            GossipRootKind.START -> when (root.children.size) {
                0 -> GossipWorkKind.PERIODIC_SYNC
                1 -> GossipWorkKind.CLEANUP
                else -> null
            }
            GossipRootKind.INITIAL_BROADCAST -> if (root.children.isEmpty()) GossipWorkKind.INITIAL_BROADCAST else null
            GossipRootKind.INITIAL_PEER -> if (root.children.isEmpty()) GossipWorkKind.INITIAL_PEER else null
        }
        check(expected == kind) { "Job root child kind is repeated or out of order" }
        val ticket = Ticket()
        records[ticket] = Record(ticket, kind)
        root.children += ticket
        ticket
    }

    fun finishRoot(lease: GossipRootLease) {
        synchronized(monitor) {
            val root = roots[lease]
            check(root != null && root.binding === association && root.state == GossipRootState.ACTIVE) {
                "Job root lease is foreign or finished"
            }
            root.state = GossipRootState.FINISHED
        }
    }

    fun rootSnapshot(): List<GossipRootSnapshot> = synchronized(monitor) {
        roots.values.map { GossipRootSnapshot(it.id, it.kind, it.state) }
    }

    fun dispatch(
        ticket: GossipWorkTicket,
        scope: CoroutineScope,
        context: CoroutineContext,
        creator: GossipJobCreator,
        body: suspend CoroutineScope.() -> Unit
    ): Job {
        val record = synchronized(monitor) {
            val original = records[ticket]
            check(original != null && original.outcome === GossipWorkOutcome.Reserved) {
                "Gossip dispatch ticket is foreign or already claimed"
            }
            original.outcome = GossipWorkOutcome.Dispatching
            original
        }
        val job = try {
            creator(scope, context, body)
        } catch (failure: Throwable) {
            synchronized(monitor) { record.outcome = GossipWorkOutcome.Unknown }
            throw failure
        }
        synchronized(monitor) { record.outcome = GossipWorkOutcome.Returned(job) }
        return job
    }

    fun snapshot(): List<GossipWorkSnapshot> = synchronized(monitor) {
        records.values.map { GossipWorkSnapshot(it.ticket, it.kind, it.outcome) }
    }
}
