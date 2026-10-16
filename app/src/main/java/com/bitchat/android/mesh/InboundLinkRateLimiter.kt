package com.bitchat.android.mesh

import java.util.concurrent.atomic.AtomicLong

/**
 * Cheap per-link token bucket applied to raw inbound frames before they are decoded or any
 * signature is checked. The defaults sit well above what a BitChat peer sends on one link
 * (a full 256-fragment transfer fits in the burst), so only an abnormal link is slowed.
 * The link table is bounded; the least recently used link is forgotten first.
 */
class InboundLinkRateLimiter(
    private val perSecond: Double = 100.0,
    private val burst: Double = 400.0,
    private val maxTrackedLinks: Int = 64,
    private val now: () -> Long = System::currentTimeMillis
) {
    private class Bucket(var tokens: Double, var updatedAt: Long)

    private val links = LinkedHashMap<String, Bucket>(16, 0.75f, true)
    private val droppedCount = AtomicLong()

    val dropped: Long get() = droppedCount.get()

    val trackedLinks: Int @Synchronized get() = links.size

    @Synchronized
    fun tryAcquire(linkID: String): Boolean {
        val time = now()
        val bucket = links[linkID] ?: Bucket(burst, time).also {
            if (links.size >= maxTrackedLinks) links.remove(links.keys.first())
            links[linkID] = it
        }
        val elapsed = (time - bucket.updatedAt).coerceAtLeast(0L)
        bucket.tokens = minOf(burst, bucket.tokens + elapsed * perSecond / 1000.0)
        bucket.updatedAt = time
        if (bucket.tokens < 1.0) {
            droppedCount.incrementAndGet()
            return false
        }
        bucket.tokens -= 1.0
        return true
    }

    @Synchronized
    fun forget(linkID: String) {
        links.remove(linkID)
    }
}
