package com.bitchat.android.mesh

import java.util.concurrent.atomic.AtomicLong

/**
 * Bounds how fast public messages may enter local state, so one sender or one flooding link
 * cannot bury the local channel. Over-budget messages are dropped silently and only counted.
 *
 * Sources should be keyed on the announced Noise key fingerprint rather than the rotatable peer
 * ID. Tracking is bounded: when the table is full, only a bucket that has fully refilled (and so
 * carries no penalty) may be evicted; otherwise new sources share one overflow bucket instead of
 * receiving a fresh full burst.
 */
class PublicInboundBudget(
    private val now: () -> Long = System::currentTimeMillis,
    private val global: Rate = Rate(perSecond = 20.0, burst = 60.0),
    private val perPeer: Rate = Rate(perSecond = 1.0, burst = 8.0),
    private val perLink: Rate = Rate(perSecond = 5.0, burst = 30.0),
    private val maxTrackedSources: Int = 512
) {
    data class Rate(val perSecond: Double, val burst: Double)

    private class Bucket(var tokens: Double, var updatedAt: Long)

    private val globalBucket = Bucket(global.burst, now())
    private val peers = LinkedHashMap<String, Bucket>(16, 0.75f, true)
    private val links = LinkedHashMap<String, Bucket>(16, 0.75f, true)
    private val peerOverflow = Bucket(perPeer.burst, now())
    private val linkOverflow = Bucket(perLink.burst, now())
    private val droppedCount = AtomicLong()

    val dropped: Long get() = droppedCount.get()

    @Synchronized
    fun tryAdmit(sourceKey: String, linkID: String?): Boolean {
        val time = now()
        val buckets = listOf(
            refill(globalBucket, global, time),
            refill(bucket(peers, sourceKey, perPeer, peerOverflow, time), perPeer, time),
            // A message without a link identity still pays into a shared link bucket.
            refill(bucket(links, linkID ?: UNKNOWN_LINK, perLink, linkOverflow, time), perLink, time)
        )
        if (buckets.any { it.tokens < 1.0 }) {
            droppedCount.incrementAndGet()
            return false
        }
        buckets.forEach { it.tokens -= 1.0 }
        return true
    }

    /** Counts a message refused before budgeting, for example an oversized payload. */
    fun recordDrop() {
        droppedCount.incrementAndGet()
    }

    private fun bucket(
        map: LinkedHashMap<String, Bucket>,
        key: String,
        rate: Rate,
        overflow: Bucket,
        time: Long
    ): Bucket {
        map[key]?.let { return it }
        if (map.size >= maxTrackedSources) {
            val idle = map.entries.firstOrNull { refill(it.value, rate, time).tokens >= rate.burst }?.key
                ?: return overflow
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
        private const val UNKNOWN_LINK = "unknown-link"
    }
}
