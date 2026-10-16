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
    fun `Wesh settings and Tor state never leave the phone by backup or device transfer`() {
        val transfer = File("src/main/res/xml/data_extraction_rules.xml").readText()
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val rules = Regex("<$section>(.*?)</$section>", RegexOption.DOT_MATCHES_ALL).find(transfer)?.groupValues?.get(1).orEmpty()
            assertTrue("$section keeps wesh_ui.xml", rules.contains("""<exclude domain="sharedpref" path="wesh_ui.xml"/>"""))
            assertTrue("$section keeps files/arti", rules.contains("""<exclude domain="file" path="arti/"/>"""))
        }
        val legacy = File("src/main/res/xml/backup_rules.xml").readText()
        assertTrue(legacy.contains("""<exclude domain="sharedpref" path="wesh_ui.xml"/>"""))
        assertTrue(legacy.contains("""<exclude domain="file" path="arti/"/>"""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifest.contains("""android:fullBackupContent="@xml/backup_rules""""))
    }
}
