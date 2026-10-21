package com.bitchat.android.ui.pave

import android.app.Application
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.ui.EncryptedWeshDraftBackend
import com.bitchat.android.ui.WeshDraftStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        fixture.store.fenceForPanic()

        edit("Synthetic callback during erase")
        runCurrent()

        assertTrue(fixture.store.drafts.value.isEmpty())
        assertEquals(1, rejections)
        assertEquals(WeshDraftStore.Persistence.WipeRequired, fixture.store.persistence.value)
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

    private fun TestScope.fixture(): Fixture {
        val backend = EncryptedWeshDraftBackend(directory.root, InMemoryConversationStorageCipher())
        return Fixture(backend, WeshDraftStore(backend, backgroundScope, StandardTestDispatcher(testScheduler)))
    }

    private data class Fixture(val backend: EncryptedWeshDraftBackend, val store: WeshDraftStore)
    private data class DraftEffects(val drafts: Map<String, String>, val persisted: Map<String, String>, val rejections: Int)

    private companion object {
        val OLD_OWNER = "11".repeat(32)
        const val FRESH_TEXT = "Synthetic fresh composer draft"
    }
}
