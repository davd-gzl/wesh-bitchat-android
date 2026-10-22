package com.bitchat.android.ui.pave

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.MessageTransport
import com.bitchat.android.util.AppConstants
import kotlin.random.Random

/** Composer byte budget: 2,000 UTF-8 bytes; the counter shows only past 1,800. */
object ComposerLimits {
    const val MAX_BYTES = 2000
    const val COUNTER_FROM_BYTES = 1800

    fun byteCount(text: String): Int = text.encodeToByteArray().size
    fun counterVisible(bytes: Int): Boolean = bytes > COUNTER_FROM_BYTES
    fun isOverLimit(bytes: Int): Boolean = bytes > MAX_BYTES
    fun canSend(text: String): Boolean = text.isNotBlank() && !isOverLimit(byteCount(text))
}

/** Delivery words of the spec (§3): En attente, Envoyé, Distribué, Lu, Échec. */
enum class PaveDelivery {
    Waiting, Sent, Delivered, Read, Failed;

    companion object {
        /** Distribué and Lu exist for private messages only: the public room has no receipts. */
        fun from(status: DeliveryStatus?, isPrivate: Boolean): PaveDelivery = when (status) {
            null, DeliveryStatus.Sending -> Waiting
            DeliveryStatus.Sent, is DeliveryStatus.PartiallyDelivered -> Sent
            is DeliveryStatus.Delivered -> if (isPrivate) Delivered else Sent
            is DeliveryStatus.Read -> if (isPrivate) Read else Sent
            is DeliveryStatus.Failed -> Failed
        }
    }
}

/** How a private message travelled, from its last attempt and then its ack (spec §3 label). */
enum class PaveTransport {
    Bluetooth, Internet;

    companion object {
        fun from(transport: MessageTransport?): PaveTransport? = when (transport) {
            MessageTransport.BLUETOOTH -> Bluetooth
            MessageTransport.INTERNET -> Internet
            null -> null
        }
    }
}

/** Hold-to-erase timing: three seconds, with haptic pulses that come closer and closer. */
object HoldToEraseTiming {
    const val HOLD_MS = 3000L

    /** Elapsed times (ms) at which a pulse fires; the gaps shrink so the pulses feel rising. */
    val PULSES_MS = longArrayOf(0, 700, 1300, 1800, 2200, 2500, 2750, 2900)

    fun progress(elapsedMs: Long): Float = (elapsedMs.coerceIn(0, HOLD_MS).toFloat() / HOLD_MS)
    fun isComplete(elapsedMs: Long): Boolean = elapsedMs >= HOLD_MS

    /** Number of pulses due once [elapsedMs] has passed. */
    fun pulsesDue(elapsedMs: Long): Int = PULSES_MS.count { it <= elapsedMs }

    /** The second half of the pulses is stronger. */
    fun isStrongPulse(index: Int): Boolean = index >= PULSES_MS.size / 2
}

/** Random, editable nickname such as "Renard 42". Never derived from the device or the person. */
object NicknameGenerator {
    val NAMES = listOf(
        "Renard", "Loutre", "Hibou", "Moineau", "Castor", "Chouette", "Lynx", "Blaireau",
        "Héron", "Martinet", "Écureuil", "Hérisson", "Mésange", "Faucon", "Belette", "Pinson",
        "Corbeau", "Lièvre", "Coquelicot", "Tilleul", "Bleuet", "Pivert", "Grillon", "Marmotte",
    )
    const val MAX_LENGTH = AppConstants.UI.MAX_NICKNAME_LENGTH

    fun generate(random: Random = Random.Default): String =
        "${NAMES[random.nextInt(NAMES.size)]} ${random.nextInt(2, 100)}"

    /** A different nickname than [current], for the "another one" button. */
    fun next(current: String, random: Random = Random.Default): String {
        var candidate = generate(random)
        repeat(8) { if (candidate != current) return candidate; candidate = generate(random) }
        return candidate
    }

    /** Applies the input rules to what the person typed. */
    fun clean(input: String): String = input.replace('\n', ' ').trimStart().take(MAX_LENGTH)

    fun isValid(nickname: String): Boolean = nickname.isNotBlank() && nickname.length <= MAX_LENGTH
}

/** The one status shown at the top of each screen. */
sealed interface PaveLinkStatus {
    data class InRange(val phones: Int) : PaveLinkStatus
    data object NobodyInRange : PaveLinkStatus
    data object BluetoothOff : PaveLinkStatus
    data class ViaInternet(val phones: Int) : PaveLinkStatus

    companion object {
        fun from(bluetoothOn: Boolean, phonesInRange: Int, internetConnected: Boolean): PaveLinkStatus = when {
            internetConnected -> ViaInternet(if (bluetoothOn) phonesInRange.coerceAtLeast(0) else 0)
            !bluetoothOn -> BluetoothOff
            phonesInRange <= 0 -> NobodyInRange
            else -> InRange(phonesInRange)
        }
    }
}

/** Two checks of the Exchange screen: they scanned you, you scanned them. */
data class ExchangeProgress(val theyScannedMe: Boolean = false, val iScannedThem: Boolean = false) {
    val complete: Boolean get() = theyScannedMe && iScannedThem
}

/**
 * Runtime permissions asked right after the Bluetooth explainer.
 * From API 31 Bluetooth has its own permissions, so Wesh never asks for location there.
 */
@SuppressLint("InlinedApi") // Guarded by sdkInt; the constants are only used on API 31+.
fun paveBluetoothRuntimePermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
    if (sdkInt >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
