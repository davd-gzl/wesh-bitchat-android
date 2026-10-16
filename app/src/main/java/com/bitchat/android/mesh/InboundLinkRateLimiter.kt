package com.bitchat.android.mesh

import java.util.concurrent.atomic.AtomicLong

/**
 * Cheap token buckets applied to raw inbound frames before they are decoded or any signature is
 * checked: one per remote device and one shared by all links. The defaults sit well above what
 * BitChat peers send (a full 256-fragment transfer fits in a device's burst), so only abnormal
 * traffic is slowed.
 *
 * Buckets are keyed on the most stable identity available (the device address, the connection
 * ID otherwise) and survive disconnects, so reconnecting does not refill them. A bucket idle for
 * a few refill windows is full again and is forgotten; when the table is full, the bucket with
 * the most tokens (the least throttled) is evicted first.
 *
 * The shared ceiling never shuts a link out entirely: each device keeps a small guaranteed
 * allowance ([minPerSecond], [minBurst]) usable while the shared bucket is empty, so busy links
 * cannot starve a quiet one.
 */
class InboundLinkRateLimiter(
    private val perSecond: Double = 100.0,
    private val burst: Double = 400.0,
    private val globalPerSecond: Double = 300.0,
    private val globalBurst: Double = 1_200.0,
    private val minPerSecond: Double = 5.0,
    private val minBurst: Double = 20.0,
    private val maxTrackedLinks: Int = 64,
    private val now: () -> Long = System::currentTimeMillis
) {
    private class Bucket(var tokens: Double, var updatedAt: Long, var reserve: Double = 0.0)

    private val links = HashMap<String, Bucket>()
    private val global = Bucket(globalBurst, now())
    private val droppedCount = AtomicLong()

    /** Idle time after which a bucket is full again and carries no state worth keeping. */
    private val idleForgetMs: Long = (AGE_OUT_REFILL_WINDOWS * 1000.0 * burst / perSecond).toLong()

    val dropped: Long get() = droppedCount.get()

    val trackedLinks: Int @Synchronized get() = links.size

    /**
     * @param deviceAddress remote address when the transport has one (preferred key).
     * @param linkID per-connection ID, used when no address is available.
     */
    @Synchronized
    fun tryAcquire(deviceAddress: String?, linkID: String): Boolean {
        val time = now()
        val key = deviceAddress?.let { "addr:$it" } ?: "link:$linkID"
        val bucket = links[key] ?: newBucket(key, time)
        bucket.reserve = minOf(minBurst, bucket.reserve + (time - bucket.updatedAt).coerceAtLeast(0L) * minPerSecond / 1000.0)
        refill(bucket, perSecond, burst, time)
        refill(global, globalPerSecond, globalBurst, time)
        when {
            bucket.tokens < 1.0 -> Unit
            global.tokens >= 1.0 -> {
                bucket.tokens -= 1.0
                global.tokens -= 1.0
                return true
            }
            // Shared ceiling reached: the link's guaranteed allowance still applies.
            bucket.reserve >= 1.0 -> {
                bucket.tokens -= 1.0
                bucket.reserve -= 1.0
                return true
            }
        }
        droppedCount.incrementAndGet()
        return false
    }

    private fun newBucket(key: String, time: Long): Bucket {
        if (links.size >= maxTrackedLinks) {
            links.entries.removeIf { time - it.value.updatedAt >= idleForgetMs }
        }
        if (links.size >= maxTrackedLinks) {
            val leastThrottled = links.entries.maxByOrNull { (_, b) ->
                minOf(burst, b.tokens + (time - b.updatedAt).coerceAtLeast(0L) * perSecond / 1000.0)
            }
            leastThrottled?.let { links.remove(it.key) }
        }
        return Bucket(burst, time, minBurst).also { links[key] = it }
    }

    private fun refill(bucket: Bucket, rate: Double, cap: Double, time: Long): Bucket {
        val elapsed = (time - bucket.updatedAt).coerceAtLeast(0L)
        bucket.tokens = minOf(cap, bucket.tokens + elapsed * rate / 1000.0)
        bucket.updatedAt = time
        return bucket
    }

    private companion object {
        const val AGE_OUT_REFILL_WINDOWS = 3.0
    }
}
