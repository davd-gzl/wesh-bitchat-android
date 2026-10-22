package com.bitchat.android.services

/** Receipt bookkeeping admission only; this does not retire a transport or its other writers. */
internal class SeenReceiptAdmission {
    internal val monitor = Any()
    private var epoch = 0L
    private var fenced = false

    internal class Token internal constructor(internal val owner: SeenReceiptAdmission, internal val epoch: Long)

    fun capture(): Token? = synchronized(monitor) {
        if (fenced) null else Token(this, epoch)
    }

    fun fenceForPanic(): Token = synchronized(monitor) {
        epoch++
        fenced = true
        Token(this, epoch)
    }

    fun admits(token: Token?): Boolean = synchronized(monitor) {
        token != null && token.owner === this && token.epoch == epoch && !fenced
    }
}
