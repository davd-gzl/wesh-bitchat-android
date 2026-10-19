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

/** Retains original dispatch outcomes; completion and cancellation do not discharge ownership. */
internal class GossipWorkOwner {
    private class Ticket : GossipWorkTicket
    private class Record(val ticket: GossipWorkTicket, val kind: GossipWorkKind) {
        var outcome: GossipWorkOutcome = GossipWorkOutcome.Reserved
    }

    private val monitor = Any()
    private val records = LinkedHashMap<GossipWorkTicket, Record>()

    fun reserve(kind: GossipWorkKind): GossipWorkTicket = synchronized(monitor) {
        val ticket = Ticket()
        records[ticket] = Record(ticket, kind)
        ticket
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
