package com.bitchat.android.mesh

import java.util.concurrent.atomic.AtomicLong

/**
 * Bounds how fast public messages may enter local state, so one sender or one flooding link
 * cannot bury the local channel. Over-budget messages are dropped silently and only counted.
 *
 * Only a Noise-authenticated key gets a bucket of its own. Every unauthenticated message (no
 * proven key, no link identity, or a full source table) pays into one shared, tighter bucket,
 * so rotating peer IDs only drains that bucket and never earns a fresh burst.
 */
class PublicInboundBudget(
    private val now: () -> Long = System::currentTimeMillis,
    private val global: Rate = Rate(perSecond = 20.0, burst = 60.0),
    private val perAuthenticatedSource: Rate = Rate(perSecond = 1.0, burst = 8.0),
    private val perLink: Rate = Rate(perSecond = 5.0, burst = 30.0),
    private val unauthenticated: Rate = Rate(perSecond = 2.0, burst = 10.0),
    private val maxTrackedSources: Int = 512
) {
    data class Rate(val perSecond: Double, val burst: Double)

    private class Bucket(var tokens: Double, var updatedAt: Long)

    private val globalBucket = Bucket(global.burst, now())
    private val sharedUnauthenticated = Bucket(unauthenticated.burst, now())
    private val sources = LinkedHashMap<String, Bucket>(16, 0.75f, true)
    private val links = LinkedHashMap<String, Bucket>(16, 0.75f, true)
    private val droppedCount = AtomicLong()

    val dropped: Long get() = droppedCount.get()

    /**
     * @param authenticatedSource key proven by a Noise session (for example `noise:<hex>`), or
     * null when the sender is not authenticated.
     */
    @Synchronized
    fun tryAdmit(authenticatedSource: String?, linkID: String?): Boolean {
        val time = now()
        val charged = LinkedHashMap<Bucket, Rate>()
        charged[globalBucket] = global
        val source = authenticatedSource?.let { tracked(sources, it, perAuthenticatedSource, time) }
        if (source != null) charged[source] = perAuthenticatedSource else charged[sharedUnauthenticated] = unauthenticated
        val link = linkID?.let { tracked(links, it, perLink, time) }
        if (link != null) charged[link] = perLink else charged[sharedUnauthenticated] = unauthenticated
        charged.forEach { (bucket, rate) -> refill(bucket, rate, time) }
        if (charged.keys.any { it.tokens < 1.0 }) {
            droppedCount.incrementAndGet()
            return false
        }
        charged.keys.forEach { it.tokens -= 1.0 }
        return true
    }

    /** Counts a message refused before budgeting, for example an oversized payload. */
    fun recordDrop() {
        droppedCount.incrementAndGet()
    }

    /** Returns null when the table is full and nothing idle can be evicted. */
    private fun tracked(map: LinkedHashMap<String, Bucket>, key: String, rate: Rate, time: Long): Bucket? {
        map[key]?.let { return it }
        if (map.size >= maxTrackedSources) {
            // Only a fully refilled bucket carries no penalty and may be forgotten.
            val idle = map.entries.firstOrNull { refill(it.value, rate, time).tokens >= rate.burst }?.key ?: return null
            map.remove(idle)
        }
        return Bucket(rate.burst, time).also { map[key] = it }
    }

    private fun refill(bucket: Bucket, rate: Rate, time: Long): Bucket {
        val elapsed = (time - bucket.updatedAt).coerceAtLeast(0L)
        bucket.tokens = minOf(rate.burst, bucket.tokens + elapsed * rate.perSecond / 1000.0)
        bucket.updatedAt = time
        return bucket
    }

    companion object {
        /** Same limit as outgoing Wesh public text (V1). */
        const val MAX_PUBLIC_TEXT_BYTES = 2_000
    }
}
