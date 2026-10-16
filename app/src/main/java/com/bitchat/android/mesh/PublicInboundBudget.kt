package com.bitchat.android.mesh

import java.util.concurrent.atomic.AtomicLong

/**
 * Bounds how fast public messages may enter local state, so one peer or one flooding link
 * cannot bury the local channel. Over-budget messages are dropped silently and only counted.
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
    private val droppedCount = AtomicLong()

    val dropped: Long get() = droppedCount.get()

    @Synchronized
    fun tryAdmit(peerID: String, linkID: String?): Boolean {
        val time = now()
        val buckets = listOfNotNull(
            refill(globalBucket, global, time),
            refill(bucket(peers, peerID, perPeer, time), perPeer, time),
            linkID?.let { refill(bucket(links, it, perLink, time), perLink, time) }
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

    private fun bucket(map: LinkedHashMap<String, Bucket>, key: String, rate: Rate, time: Long): Bucket =
        map.getOrPut(key) {
            if (map.size >= maxTrackedSources) map.remove(map.keys.first())
            Bucket(rate.burst, time)
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
