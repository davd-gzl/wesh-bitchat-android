package com.bitchat.android.testhook

import android.app.Application
import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.services.WeshContactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Debug-only seeding of a QR contact with an npub, for emulator Internet runs without Bluetooth. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WeshDebugContactSeedTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store(name: String) = WeshContactStore(
        SecureIdentityStateManager(context.getSharedPreferences("synthetic-seed-$name", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }, true),
        context.getSharedPreferences("synthetic-seed-blocks-$name", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    )

    @Test
    fun `a seeded contact is Internet-reachable through the same enrollment checks as a QR scan`() {
        val contacts = store("ok")
        val peer = NostrIdentity.generate()
        val noise = "ab".repeat(32)

        assertTrue(WeshDebugContactSeed.seed(contacts, ownedNpubs = emptySet(), noiseHex = noise, nickname = "Synthetic", npub = peer.npub))
        val contact = contacts.contacts.value.single()
        assertEquals(peer.npub, contact.deliveryNpub)
        assertTrue(contact.npubProven)
    }

    @Test
    fun `a bad key, a bad npub or my own npub is refused`() {
        val contacts = store("refused")
        val mine = NostrIdentity.generate()
        assertFalse(WeshDebugContactSeed.seed(contacts, emptySet(), "zz".repeat(32), "Synthetic", NostrIdentity.generate().npub))
        assertFalse(WeshDebugContactSeed.seed(contacts, emptySet(), "ab".repeat(32), "Synthetic", "npub1invalid"))
        assertFalse(WeshDebugContactSeed.seed(contacts, setOf(mine.npub), "ab".repeat(32), "Synthetic", mine.npub))
        assertNull(contacts.contacts.value.firstOrNull()?.deliveryNpub)
    }

    @Test
    fun `the test hook exists only in debug sources, never in a release build`() {
        // Release = main + release source sets. Unit tests run from the app module directory.
        val releaseSources = listOf(File("src/main"), File("src/release")).filter(File::exists)
        assertTrue("main source set not found from ${File(".").absolutePath}", File("src/main/AndroidManifest.xml").exists())
        val leaks = releaseSources.flatMap { root -> root.walkTopDown().filter(File::isFile).toList() }
            .filter { file ->
                val text = runCatching { file.readText() }.getOrDefault("")
                "TEST_HOOK" in text || "testhook" in text || "WeshDebugContactSeed" in text
            }
        assertEquals(emptyList<File>(), leaks)
        assertTrue(File("src/debug/java/com/bitchat/android/testhook/WeshDebugContactSeed.kt").exists())
        assertTrue(File("src/debug/AndroidManifest.xml").readText().contains("com.bitchat.droid.TEST_HOOK"))
    }
}
