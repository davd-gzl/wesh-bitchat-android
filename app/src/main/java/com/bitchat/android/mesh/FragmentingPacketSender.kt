package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shared transport send wrapper that applies bitchat packet fragmentation before a transport
 * writes packets to its concrete medium.
 */
class FragmentingPacketSender(
    private val scope: CoroutineScope,
    private val fragmentManager: FragmentManager?,
    private val logTag: String,
    private val interFragmentDelayMs: Long = 20L
) {
    fun send(
        routed: RoutedPacket,
        description: String,
        sendSingle: (RoutedPacket) -> Boolean
    ): Boolean {
        val packets = packetsForTransport(routed) ?: return false
        val total = packets.size

        if (total <= 1) {
            return sendSingle(routed.copy(packet = packets.first()))
        }

        Log.d(logTag, "Fragmenting packet type ${routed.packet.type} into $total fragments for $description")

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var sent = 0
            for (packet in packets) {
                if (!isActive) return@launch

                val delivered = try {
                    sendSingle(routed.copy(packet = packet))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.e(logTag, "Fragment send failed for $description: ${e.message}", e)
                    false
                }

                if (!delivered) {
                    if (!isActive) return@launch
                    Log.w(logTag, "Stopping fragmented send for $description after $sent/$total fragments")
                    return@launch
                }

                sent += 1
                if (sent < total) {
                    delay(interFragmentDelayMs)
                }
            }
        }
        return job.start()
    }

    internal fun packetsForTransport(routed: RoutedPacket): List<BitchatPacket>? {
        val packet = routed.packet
        if (packet.type == MessageType.FRAGMENT.value) {
            return listOf(packet)
        }

        val manager = fragmentManager ?: return listOf(packet)
        return try {
            // Receivers hard-cap reassembly at MAX_FRAGMENTS_PER_ID; sending more
            // fragments would be undeliverable, so reject here instead.
            val fragments = manager.createFragments(
                packet,
                com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID
            )
            if (fragments.isEmpty()) {
                Log.e(logTag, "Fragment manager returned no packets for packet type ${packet.type}")
                null
            } else {
                fragments
            }
        } catch (e: Exception) {
            Log.e(logTag, "Fragment creation failed for packet type ${packet.type}: ${e.message}", e)
            null
        }
    }
}
