package com.bitchat.android.protocol

import com.bitchat.android.util.AppConstants

/**
 * Receive-side ceilings on the decoded payload of a packet, per type, matching the iOS client.
 *
 * A compressed frame declares its original size, and fragment reassembly turns many small
 * frames into one large one, so a single frame-wide ceiling would let any type grow to the
 * file-transfer size. Each type is capped at no less than twice the largest payload a shipping
 * BitChat client produces for it; senders are unchanged.
 *
 * | Type                          | Largest legitimate payload                    | Cap                |
 * |-------------------------------|-----------------------------------------------|--------------------|
 * | FILE_TRANSFER, NOISE_ENCRYPTED| a file up to the Android media limit          | MAX_PAYLOAD_LENGTH |
 * |                               | (compressed: see [maxInflatedBytes])          |                    |
 * | MESSAGE                       | 65,535 B (public text uses v1 frames)         | 128 KiB            |
 * | REQUEST_SYNC                  | ~2.1 KB of filters                            | 8 KiB              |
 * | FRAGMENT                      | ≤ 513 B: 13 B header + chunk                  | 1,280 B            |
 * | ANNOUNCE, LEAVE, NOISE_HANDSHAKE, VOICE_FRAME | well under 1 KB               | 4 KiB              |
 * | types unknown to this client  | a v1 frame (65,535 B) stays relayable         | 128 KiB            |
 */
object PacketPayloadLimits {
    /**
     * DEFLATE cannot expand by more than this: every 258-byte match costs at least two bits.
     * A declared size beyond this multiple of the compressed bytes is rejected before inflating.
     */
    const val MAX_DEFLATE_RATIO = 1_032

    private const val FRAGMENT_BYTES = 1_280
    private const val CONTROL_BYTES = 4 * 1024
    private const val REQUEST_SYNC_BYTES = 8 * 1024
    private const val TWICE_V1_FRAME_BYTES = 128 * 1024

    /**
     * Everything a frame carries besides its payload: v2 header, sender, recipient, the widest
     * route (255 hops), the compressed-size field, signature and the most padding added.
     */
    private const val MAX_FRAME_OVERHEAD_BYTES = 16 + 8 + 8 + 1 + 255 * 8 + 4 + 64 + 255

    /** Largest decoded payload accepted for a packet of [type], compressed or not. */
    fun maxPayloadBytes(type: UByte): Int = when (MessageType.fromValue(type)) {
        MessageType.FILE_TRANSFER, MessageType.NOISE_ENCRYPTED -> AppConstants.Protocol.MAX_PAYLOAD_LENGTH
        MessageType.MESSAGE -> TWICE_V1_FRAME_BYTES
        MessageType.REQUEST_SYNC -> REQUEST_SYNC_BYTES
        MessageType.FRAGMENT -> FRAGMENT_BYTES
        MessageType.ANNOUNCE, MessageType.LEAVE, MessageType.NOISE_HANDSHAKE, MessageType.VOICE_FRAME -> CONTROL_BYTES
        null -> TWICE_V1_FRAME_BYTES
    }

    /** Largest encoded frame, padding included, that can still decode as a packet of [type]. */
    fun maxFrameBytes(type: UByte): Int = maxPayloadBytes(type) + MAX_FRAME_OVERHEAD_BYTES

    /** False when the inbound policy refuses media: compressed files are then not inflated at all. */
    @Volatile
    var acceptCompressedMedia: Boolean = true

    /** Longest public text accepted, the same limit as outgoing Wesh public text. */
    const val PUBLIC_TEXT_BYTES = 2_000

    /** Largest frame, padding included, that can carry public text within [PUBLIC_TEXT_BYTES]. */
    const val PUBLIC_TEXT_FRAME_BYTES = PUBLIC_TEXT_BYTES + MAX_FRAME_OVERHEAD_BYTES

    /**
     * Largest size a compressed payload of [type] may declare, or null when that type is never
     * sent compressed. Ciphertext does not shrink, so no client compresses NOISE_ENCRYPTED; a
     * compressed file inflates at most to what a fragment set may buffer; public text inflates
     * at most to the public text limit.
     */
    fun maxInflatedBytes(type: UByte, broadcast: Boolean): Int? = when (MessageType.fromValue(type)) {
        MessageType.NOISE_ENCRYPTED -> null
        MessageType.FILE_TRANSFER -> if (acceptCompressedMedia) AppConstants.Fragmentation.MAX_FRAGMENT_TOTAL_BYTES else null
        MessageType.MESSAGE -> if (broadcast) PUBLIC_TEXT_BYTES else maxPayloadBytes(type)
        else -> maxPayloadBytes(type)
    }
}
