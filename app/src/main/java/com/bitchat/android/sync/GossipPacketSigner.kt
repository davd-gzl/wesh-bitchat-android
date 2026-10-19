package com.bitchat.android.sync

import com.bitchat.android.protocol.BitchatPacket

/** Gossip-only signing path; routing and canonical bytes come from the actual packet codec. */
internal fun signGossipPacket(
    packet: BitchatPacket,
    prepareRoute: (BitchatPacket) -> BitchatPacket,
    signBytes: (ByteArray) -> ByteArray?
): BitchatPacket? {
    return try {
        val withRoute = prepareRoute(packet)
        val canonical = withRoute.toBinaryDataForSigning() ?: return withRoute
        val signature = signBytes(canonical)
        if (signature != null) withRoute.copy(signature = signature) else withRoute
    } catch (_: Exception) {
        packet
    }
}
