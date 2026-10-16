package com.bitchat.android.geohash

import android.app.Application
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class WeshNoOutboundNetworkTest {
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `Wesh never reverse geocodes`() = runBlocking {
        val provider = GeocoderFactory.get(RuntimeEnvironment.getApplication(), weshLocalOnly = true)
        assertSame(NoGeocoderProvider, provider)
        assertTrue(provider.getFromLocation(0.0, 0.0, 1).isEmpty())
    }

    @Test
    fun `no reverse geocoding web service or GitHub release client ships in the app`() {
        listOf("nominatim", "api.github.com", "GitHubReleaseClient", "releases/latest").forEach { needle ->
            val offenders = sources.filter { it.readText().contains(needle, ignoreCase = true) }.map { it.name }
            assertTrue("$needle still referenced by $offenders", offenders.isEmpty())
        }
        assertFalse(File("src/main/java/com/bitchat/android/hotspot").exists())
    }
}
