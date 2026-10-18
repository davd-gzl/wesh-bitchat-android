package com.bitchat.android.ui.pave

import android.app.Application
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.ui.EncryptedWeshDraftBackend
import com.bitchat.android.ui.WeshDraftStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic owned storage and explicitly released callbacks, without a transport or live key owner. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerDraftCallbackGenerationTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `retained composer callback is rejected while panic remains fenced`() = runTest {
        val fixture = fixture()
        fixture.store.load(OLD_OWNER)
        var rejections = 0
        val edit = composerDraftEditCallback(fixture.store, "public") { rejections++ }
        assertTrue(fixture.store.panicClear())

        edit("Synthetic callback during erase")
        runCurrent()

        assertTrue(fixture.store.drafts.value.isEmpty())
        assertEquals(1, rejections)
        assertEquals(WeshDraftStore.Persistence.Wiped, fixture.store.persistence.value)
    }

    @Test fun `retained public edit cannot replace fresh text after verified owner reset`() = runTest {
        assertRetainedEditRefused("public")
    }

    @Test fun `retained private edit cannot write into the new local owners encrypted map`() = runTest {
        assertRetainedEditRefused(PRIVATE_DESTINATION)
    }

    @Test fun `fresh callback after verified reset edits and persists without rejection feedback`() = runTest {
        val fixture = fixture()
        fixture.store.load(OLD_OWNER)
        assertTrue(fixture.store.panicClear())
        fixture.store.load(NEW_OWNER)
        assertEquals(WeshDraftStore.Persistence.Confirmed, fixture.store.persistence.value)
        var rejections = 0
        val edit = composerDraftEditCallback(fixture.store, "public") { rejections++ }

        edit(FRESH_TEXT)
        runCurrent()

        assertEquals(DraftEffects(mapOf("public" to FRESH_TEXT), mapOf("public" to FRESH_TEXT), 0),
            DraftEffects(fixture.store.drafts.value, fixture.backend.load(NEW_OWNER), rejections))
        assertEquals(WeshDraftStore.Persistence.Confirmed, fixture.store.persistence.value)
    }

    @Test fun `oversized current callback retains stored text and reports the existing rejection feedback`() = runTest {
        val fixture = fixture()
        fixture.store.load(OLD_OWNER)
        assertTrue(fixture.store.save(fixture.store.epoch, "public", FRESH_TEXT))
        runCurrent()
        var rejections = 0
        val edit = composerDraftEditCallback(fixture.store, "public") { rejections++ }

        edit("x".repeat(WeshDraftStore.MAX_DRAFT_BYTES + 1))
        runCurrent()

        assertEquals(DraftEffects(mapOf("public" to FRESH_TEXT), mapOf("public" to FRESH_TEXT), 1),
            DraftEffects(fixture.store.drafts.value, fixture.backend.load(OLD_OWNER), rejections))
        assertEquals(WeshDraftStore.Persistence.Confirmed, fixture.store.persistence.value)
    }

    private suspend fun TestScope.assertRetainedEditRefused(destination: String) {
        val fixture = fixture()
        fixture.store.load(OLD_OWNER)
        assertTrue(fixture.store.save(fixture.store.epoch, destination, "Synthetic old saved draft"))
        runCurrent()
        var rejections = 0
        val edit = composerDraftEditCallback(fixture.store, destination) { rejections++ }
        val oldEpoch = fixture.store.epoch
        val release = CompletableDeferred<Unit>()
        // Start through the actual delivery barrier before changing ownership.
        val delayedEdit = async(start = CoroutineStart.UNDISPATCHED) {
            release.await()
            edit("Synthetic retained old callback text")
        }
        try {
            assertTrue(fixture.store.panicClear())
            fixture.store.load(NEW_OWNER)
            assertNotEquals(oldEpoch, fixture.store.epoch)
            assertEquals(WeshDraftStore.Persistence.Confirmed, fixture.store.persistence.value)
            assertTrue(fixture.store.save(fixture.store.epoch, destination, FRESH_TEXT))
            runCurrent()
            assertEquals(mapOf(destination to FRESH_TEXT), fixture.backend.load(NEW_OWNER))

            release.complete(Unit)
            delayedEdit.await()
            // Drain the real process-owned writer, not merely the edit callback.
            runCurrent()

            assertEquals(DraftEffects(mapOf(destination to FRESH_TEXT), mapOf(destination to FRESH_TEXT), 1),
                DraftEffects(fixture.store.drafts.value, fixture.backend.load(NEW_OWNER), rejections))
            assertEquals(WeshDraftStore.Persistence.Confirmed, fixture.store.persistence.value)
        } finally {
            release.complete(Unit)
            delayedEdit.cancelAndJoin()
        }
    }

    private fun TestScope.fixture(): Fixture {
        val backend = EncryptedWeshDraftBackend(directory.root, InMemoryConversationStorageCipher())
        return Fixture(backend, WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler)))
    }

    private data class Fixture(val backend: EncryptedWeshDraftBackend, val store: WeshDraftStore)
    private data class DraftEffects(val drafts: Map<String, String>, val persisted: Map<String, String>, val rejections: Int)

    private companion object {
        val OLD_OWNER = "11".repeat(32)
        val NEW_OWNER = "22".repeat(32)
        val PRIVATE_DESTINATION = "private:" + "33".repeat(32)
        const val FRESH_TEXT = "Synthetic fresh composer draft"
    }
}
