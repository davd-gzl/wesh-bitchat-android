package com.bitchat.android.sync

import kotlinx.coroutines.CoroutineScope
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
