package com.bitchat.android.mesh

/** What a bound private send over Bluetooth achieved; decides the Internet fallback (spec §3). */
enum class BoundSendOutcome {
    /** Every fragment was accepted by the radio. */
    SENT,
    /** Nothing reached the radio and the session still checks out: another route may be used. */
    NOT_SENT,
    /** Some bytes may have left (a later fragment failed, a timeout): wait for the ack (rule 4). */
    UNCONFIRMED,
    /** The session or the contact's key no longer matches: never send this message elsewhere. */
    AUTH_FAILED;

    companion object {
        /**
         * Outcome after fragment [index] got [accepted] (null: timeout or error), with the
         * authorization re-checked afterwards; null means carry on with the next fragment.
         */
        fun afterFragment(index: Int, accepted: Boolean?, stillAuthorized: Boolean): BoundSendOutcome? = when {
            !stillAuthorized -> AUTH_FAILED
            accepted == true -> null
            index == 0 && accepted == false -> NOT_SENT
            else -> UNCONFIRMED
        }
    }
}
