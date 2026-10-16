package com.bitchat.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Play policy surface of the Wesh manifest; the merged release manifest is checked in CI review. */
class WeshManifestContractTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun declarations(permission: String): List<String> =
        Regex("""<uses-permission[^>]*android:name="android\.permission\.$permission"[^>]*/>""").findAll(manifest).map { it.value }.toList()

    @Test
    fun `unused sensitive permissions are only declared for removal`() {
        listOf(
            "ACCESS_BACKGROUND_LOCATION", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
            "RECORD_AUDIO", "NEARBY_WIFI_DEVICES", "ACCESS_LOCAL_NETWORK",
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "FOREGROUND_SERVICE_LOCATION", "FOREGROUND_SERVICE_DATA_SYNC"
        ).forEach { permission ->
            val found = declarations(permission)
            assertEquals("$permission must be declared once, for removal", 1, found.size)
            assertTrue("$permission must use tools:node=remove", found.single().contains("""tools:node="remove""""))
        }
    }

    @Test
    fun `internet stays for the planned Tor mode and location stays limited to legacy Bluetooth`() {
        assertEquals(1, declarations("INTERNET").size)
        listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION").forEach {
            assertTrue(declarations(it).single().contains("""android:maxSdkVersion="30""""))
        }
        assertTrue(declarations("BLUETOOTH_SCAN").single().contains("neverForLocation"))
    }

    @Test
    fun `mesh service is a connected device service only and no APK sharing surface ships`() {
        assertTrue(manifest.contains("""android:foregroundServiceType="connectedDevice""""))
        assertFalse(manifest.contains("dataSync"))
        assertFalse(manifest.contains("HotspotActivity"))
        assertFalse(manifest.contains("SystemForegroundService"))
        assertFalse(manifest.contains("BitChat"))
    }

    @Test
    fun `no location SDK or location feature ships and background location is never requested`() {
        assertFalse(File("build.gradle.kts").readText().contains("gms"))
        assertFalse(File("gradle.lockfile").readText().contains("com.google.android.gms:"))
        assertFalse(File("src/main/java/com/bitchat/android/geohash").exists())
        assertFalse(manifest.contains("GeohashPickerActivity"))
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        listOf(
            "com.google.android.gms", "android.location.Geocoder", "requestLocationUpdates",
            "getLastKnownLocation", "getCurrentLocation", "ACCESS_BACKGROUND_LOCATION"
        ).forEach { needle ->
            val offenders = sources.filter { it.readText().contains(needle) }.map { it.name }
            assertTrue("$needle still referenced by $offenders", offenders.isEmpty())
        }
    }
}
