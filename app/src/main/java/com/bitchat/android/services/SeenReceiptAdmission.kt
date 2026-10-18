package com.bitchat.android.services

/** Receipt bookkeeping admission only; this does not retire a transport or its other writers. */
internal class SeenReceiptAdmission {
    internal val monitor = Any()
    private var epoch = 0L
    private var fenced = false
    private var clearConfirmed = false

    internal class Token internal constructor(internal val owner: SeenReceiptAdmission, internal val epoch: Long)

    fun capture(): Token? = synchronized(monitor) {
        if (fenced) null else Token(this, epoch)
    }

    fun fenceForPanic(): Token = synchronized(monitor) {
        epoch++
        fenced = true
        clearConfirmed = false
        Token(this, epoch)
    }

    fun admits(token: Token?): Boolean = synchronized(monitor) {
        token != null && token.owner === this && token.epoch == epoch && !fenced
    }

    fun ownsPanic(token: Token): Boolean = synchronized(monitor) {
        token.owner === this && token.epoch == epoch && fenced
    }

    fun confirmClear(token: Token, confirmed: Boolean) = synchronized(monitor) {
        if (ownsPanic(token)) clearConfirmed = confirmed
    }

    /** Called only from the successful all-store recovery continuation. */
    fun resumeAfterVerifiedPanic(token: Token): Boolean = synchronized(monitor) {
        if (!ownsPanic(token) || !clearConfirmed) return@synchronized false
        fenced = false
        clearConfirmed = false
        true
    }

    fun retainFenceAfterFailure(token: Token) = synchronized(monitor) {
        if (token.owner === this && token.epoch == epoch) {
            fenced = true
            clearConfirmed = false
        }
    }
}
