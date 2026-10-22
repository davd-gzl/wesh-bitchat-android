package com.bitchat.android.nostr

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class NostrAliasRegistryTest {
    // The registry is process-wide and another class may have initialized it already.
    @Before fun fresh() = NostrAliasRegistry.resetForTesting()
    @After fun done() = NostrAliasRegistry.resetForTesting()

    @Test
    fun `aliases stored under the inherited geohash name move to the Nostr registry`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("geohash_alias_registry", Context.MODE_PRIVATE).edit()
            .putString("nostr_0011223344556677", "00".repeat(32)).commit()

        NostrAliasRegistry.initialize(context)

        assertEquals("00".repeat(32), NostrAliasRegistry.get("nostr_0011223344556677"))
        assertEquals("00".repeat(32),
            context.getSharedPreferences("nostr_alias_registry", Context.MODE_PRIVATE).getString("nostr_0011223344556677", null))
        assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/geohash_alias_registry.xml").exists())
    }
}
