package info.guardianproject.arti

import java.util.concurrent.atomic.AtomicInteger

/**
 * Up while the Arti directory is being wiped. Starts check it after registering themselves, and
 * a wipe raises it before waiting for registered starts, so no start can recreate the directory
 * after a wipe reported success.
 */
object ArtiWipeLatch {
    private val wipes = AtomicInteger(0)

    fun isWiping(): Boolean = wipes.get() > 0

    fun begin() {
        wipes.incrementAndGet()
    }

    fun end() {
        wipes.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}
