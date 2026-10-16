package com.bitchat.android.ui.pave

import org.junit.Assert.assertEquals
import org.junit.Test

class ExchangeScreenTest {
    @Test
    fun `success card waits a moment before opening the chat`() {
        assertEquals(3000L, EXCHANGE_SUCCESS_HOLD_MS)
    }
}
