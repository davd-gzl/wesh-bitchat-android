package com.bitchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * How a private message last travelled: the last send attempt, then the transport its ack came
 * back on. Local metadata only, never on the wire; stored inside the encrypted message payload.
 */
@Parcelize
enum class MessageTransport : Parcelable {
    BLUETOOTH,
    INTERNET
}
