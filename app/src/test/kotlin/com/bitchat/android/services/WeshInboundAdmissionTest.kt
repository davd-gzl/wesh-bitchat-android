package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class WeshInboundAdmissionTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var repository: ConversationRepository
    private val enrolled = "e".repeat(64)
    private val stranger = "5".repeat(64)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "wesh-admission-${UUID.randomUUID()}.db"
        repository = ConversationRepository(context, Dispatchers.Unconfined, databaseName, InMemoryConversationStorageCipher())
        AppStateStore.setConversationRepositoryForTest(repository)
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        WeshMessageRequests.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
        repository.closeForTest()
        context.deleteDatabase(databaseName)
        WeshMessageRequests.clear()
    }

    private fun gate(fingerprints: Map<String, String?>) = IncomingMessageAdmission.QrEnrollment(
        fingerprintOf = { fingerprints[it] },
        isEnrolled = { it == enrolled }
    )

    private fun privateFrom(peerID: String, id: String) = BitchatMessage(
        id = id, sender = "Synthetic $peerID", content = "Synthetic secret", timestamp = Date(1L),
        isPrivate = true, senderPeerID = peerID
    )

    @Test
    fun `private message from an identity not enrolled by QR is neither stored nor dispatched`() {
        val admitted = IncomingMessageAdmission.admitToAppState(
            privateFrom("peer-b", "from-stranger"), qrEnrollment = gate(mapOf("peer-b" to stranger))
        )

        assertFalse(admitted)
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
        val request = WeshMessageRequests.requests.value.single()
        assertEquals("peer-b", request.peerID)
        assertEquals("Synthetic peer-b", request.nickname)
        assertEquals(stranger, request.fingerprint)
        assertFalse(request.toString().contains("Synthetic secret"))
    }

    @Test
    fun `private message without an authenticated identity fails closed`() {
        assertFalse(
            IncomingMessageAdmission.admitToAppState(
                privateFrom("peer-c", "no-session"), qrEnrollment = gate(emptyMap())
            )
        )
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
        assertEquals(listOf("peer-c"), WeshMessageRequests.requests.value.map { it.peerID })
    }

    @Test
    fun `private message from a QR enrolled identity is admitted`() {
        assertTrue(
            IncomingMessageAdmission.admitToAppState(
                privateFrom("peer-a", "from-contact"), qrEnrollment = gate(mapOf("peer-a" to enrolled))
            )
        )
        assertTrue(WeshMessageRequests.requests.value.isEmpty())
    }

    @Test
    fun `public messages are not gated by QR enrollment`() {
        val public = BitchatMessage(id = "public", sender = "Synthetic", content = "hello", timestamp = Date(1L), senderPeerID = "peer-b")
        assertTrue(IncomingMessageAdmission.admitToAppState(public, qrEnrollment = gate(emptyMap())))
        assertTrue(WeshMessageRequests.requests.value.isEmpty())
    }

    @Test
    fun `repeated requests from one identity stay a single bounded entry`() {
        repeat(3) { index ->
            IncomingMessageAdmission.admitToAppState(privateFrom("peer-b", "m$index"), qrEnrollment = gate(mapOf("peer-b" to stranger)))
        }
        assertEquals(1, WeshMessageRequests.requests.value.size)
        repeat(40) { index ->
            val peer = "peer-x$index"
            IncomingMessageAdmission.admitToAppState(privateFrom(peer, "x$index"), qrEnrollment = gate(mapOf(peer to index.toString().padStart(64, '0'))))
        }
        assertEquals(32, WeshMessageRequests.requests.value.size)
    }
}
