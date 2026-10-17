package com.bitchat.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeshNoOutboundNetworkTest {
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `no reverse geocoding web service or GitHub release client ships in the app`() {
        listOf("nominatim", "api.github.com", "GitHubReleaseClient", "releases/latest").forEach { needle ->
            val offenders = sources.filter { it.readText().contains(needle, ignoreCase = true) }.map { it.name }
            assertTrue("$needle still referenced by $offenders", offenders.isEmpty())
        }
        assertFalse(File("src/main/java/com/bitchat/android/hotspot").exists())
    }
}
