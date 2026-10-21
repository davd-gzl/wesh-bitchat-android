package com.bitchat.android.service

/** Refusal preserves any first accepted product as existing owner debt, never fresh authority. */
internal sealed interface HolderPairRefusal<out B : Any> {
    data object BeforeFirst : HolderPairRefusal<Nothing>
    class AfterFirst<B : Any>(val product: B) : HolderPairRefusal<B>
}

/** Publish neither product until both acquisitions are available. All actions are required. */
internal fun <B : Any, U : Any> makeHolderRuntimePairAction(
    acquireBluetooth: () -> HolderAcquisitionResult<B>,
    acquireUnified: () -> HolderAcquisitionResult<U>,
    available: (B, U) -> Unit,
    refused: (HolderPairRefusal<B>) -> Unit
): () -> Boolean = action@{
    val bluetooth = when (val first = acquireBluetooth()) {
        is HolderAcquisitionResult.Available -> first.product
        HolderAcquisitionResult.RefusedBeforeAcquisition -> {
            refused(HolderPairRefusal.BeforeFirst)
            return@action false
        }
    }
    when (val second = acquireUnified()) {
        is HolderAcquisitionResult.Available -> {
            available(bluetooth, second.product)
            true
        }
        HolderAcquisitionResult.RefusedBeforeAcquisition -> {
            refused(HolderPairRefusal.AfterFirst(bluetooth))
            false
        }
    }
}

/** Runs in the caller's coroutine; it creates no scope or dispatcher. */
internal suspend fun <T : Any, R> dispatchHolderAcquisition(
    acquire: () -> HolderAcquisitionResult<T>,
    available: suspend (T) -> R,
    refused: suspend () -> R
): R = when (val result = acquire()) {
    is HolderAcquisitionResult.Available -> available(result.product)
    HolderAcquisitionResult.RefusedBeforeAcquisition -> refused()
}

/** The receiver's entire existing work retains its completion boundary on every return. */
internal suspend fun completeHolderReceiverWork(
    body: suspend () -> Unit,
    finish: () -> Unit
) {
    try { body() } finally { finish() }
}

/** Recheck at invocation, after the caller's delay, before the real suspend continuation. */
internal fun makeAttachedHolderInitializer(
    isAttached: () -> Boolean,
    initialize: suspend () -> Unit
): suspend () -> Boolean = {
    if (!isAttached()) false else {
        initialize()
        true
    }
}
