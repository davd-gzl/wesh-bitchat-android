package com.bitchat.android.ui.pave

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingFlowTest {
    @Test
    fun `older Android asks for location for Bluetooth, so Wesh only says it does not use it`() {
        assertEquals("Wesh n’utilise pas votre position.", PaveStringResources.strings("values-fr").getValue("wesh_pave_bt_legacy_no_gps"))
        assertEquals("Wesh does not use your location.", PaveStringResources.strings("values").getValue("wesh_pave_bt_legacy_no_gps"))
    }
}
