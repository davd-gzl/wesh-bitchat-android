package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Retained UI-shaped callbacks are an admission contract, not a measured navigation exploit. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class ChatViewModelNicknameGenerationTest {
    private lateinit var context: Context
    private lateinit var plainName: String
    private lateinit var policyName: String
    private lateinit var plain: SharedPreferences
    private lateinit var preferences: WeshPreferences
    private lateinit var dataManager: DataManager
    private lateinit var state: ChatState
    private lateinit var viewModel: ChatViewModel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val announcements = AtomicInteger()
    private val panicInProgress = MutableStateFlow(false)
    private var policyCommitSucceeds = true

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        plainName = "synthetic-nickname-${UUID.randomUUID()}"
        policyName = "synthetic-nickname-policy-${UUID.randomUUID()}"
        plain = context.getSharedPreferences(plainName, Context.MODE_PRIVATE)
        assertTrue(plain.edit().putString("nickname", ORIGINAL).commit())
        preferences = WeshPreferences(context.getSharedPreferences(policyName, Context.MODE_PRIVATE),
            Dispatchers.IO, commitEditor = { editor ->
                if (policyCommitSucceeds) editor.commit() else { editor.apply(); false }
            }, meetingBackendFactory = {
                // This nickname fixture owns no meeting-note data or live storage.
                object : WeshMeetingNoteBackend {
                    override suspend fun load(): String? = null
                    override suspend fun write(text: String) = error("Unexpected meeting-note write in nickname fixture")
                    override suspend fun erase() = Unit
                }
            })
        val dataContext = mock<Context>()
        org.mockito.kotlin.whenever(dataContext.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE))
            .thenReturn(plain)
        dataManager = DataManager(dataContext)
        state = ChatState(scope)
        state.setNickname(ORIGINAL)
        val mesh = mock<MeshService>()
        doAnswer { announcements.incrementAndGet(); null }.`when`(mesh).sendBroadcastAnnounce()
        // Execute the actual setter without constructing Bluetooth, Tor, or Keystore services.
        viewModel = Mockito.mock(ChatViewModel::class.java, Mockito.CALLS_REAL_METHODS)
        inject("state", state)
        inject("dataManager", dataManager)
        inject("unifiedMeshService", mesh)
        inject("weshPreferences\$delegate", lazyOf(preferences))
        inject("weshPanicInProgress", panicInProgress)
    }

    @After fun tearDown() {
        scope.cancel()
        context.getSharedPreferences(plainName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(policyName, Context.MODE_PRIVATE).edit().clear().commit()
        AppStateStore.setNickname("")
    }

    @Test fun `retained profile save while policy is fenced cannot change nickname or announce`() = runBlocking {
        val save = profileSave("Synthetic stale profile")
        preferences.fenceForPanic()
        installResetNickname()
        val before = snapshot()

        save()

        assertEquals(before, snapshot())
    }

    @Test fun `profile callback captured before panic cannot write after verified resume`() = runBlocking {
        val save = profileSave("Synthetic stale profile")
        val oldEpoch = preferences.resetEpoch.value
        preferences.fenceForPanic()
        installResetNickname()
        assertTrue(preferences.clearForPanic())
        preferences.resumeAfterVerifiedPanic()
        assertNotEquals(oldEpoch, preferences.resetEpoch.value)
        val before = snapshot()

        save()

        assertEquals(before, snapshot())
    }

    @Test fun `fresh profile callback after verified resume changes nickname and announces once`() = runBlocking {
        preferences.fenceForPanic()
        installResetNickname()
        assertTrue(preferences.clearForPanic())
        preferences.resumeAfterVerifiedPanic()

        profileSave(FRESH)()

        assertEquals(NicknameEffects(FRESH, FRESH, FRESH, 1), snapshot())
    }

    @Test fun `failed recovery refuses newly captured profile save even after progress indicator clears`() = runBlocking {
        preferences.fenceForPanic()
        installResetNickname()
        policyCommitSucceeds = false
        assertFalse(preferences.clearForPanic())
        panicInProgress.value = false
        val save = profileSave("Synthetic save during failed recovery")
        val before = snapshot()

        save()

        assertEquals(before, snapshot())
    }

    @Test fun `whole completion admission rejects stale effects and permits fresh unchanged nickname completion`() = runBlocking {
        val epoch = preferences.resetEpoch.value
        var completions = 0
        val completion = {
            preferences.withCurrentEpoch(epoch) {
                // The welcome also has effects when no nickname setter is needed.
                preferences.welcomed = true
                completions++
            }
        }
        preferences.fenceForPanic()
        assertFalse(completion())
        assertFalse(preferences.welcomed)
        assertEquals(0, completions)
        assertTrue(preferences.clearForPanic())
        preferences.resumeAfterVerifiedPanic()
        assertFalse(completion())
        assertFalse(preferences.welcomed)
        assertEquals(0, completions)

        assertTrue(preferences.withCurrentEpoch(preferences.resetEpoch.value) {
            preferences.welcomed = true
            completions++
        })
        assertTrue(preferences.welcomed)
        assertEquals(1, completions)
        assertEquals(0, announcements.get())
    }

    @Test fun `admitted action failure propagates and does not retain the policy lock`() = runBlocking {
        val failure = IllegalStateException("Synthetic callback failure")
        try {
            preferences.withCurrentEpoch(preferences.resetEpoch.value) { throw failure }
            fail("Callback failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        preferences.fenceForPanic()
        assertTrue(preferences.clearForPanic())
        preferences.resumeAfterVerifiedPanic()
        profileSave(FRESH)()
        assertEquals(NicknameEffects(FRESH, FRESH, FRESH, 1), snapshot())
    }

    private fun profileSave(nickname: String): () -> Unit {
        val epoch = preferences.resetEpoch.value
        return { invokeNicknameSetter(nickname, epoch) }
    }

    private fun invokeNicknameSetter(nickname: String, expectedEpoch: Long) {
        viewModel.setNickname(nickname, expectedEpoch)
    }

    private suspend fun installResetNickname() {
        assertTrue(savePanicNickname(dataManager, RESET))
        state.setNickname(RESET)
    }

    private fun snapshot() = NicknameEffects(plain.getString("nickname", null),
        state.nickname.value, AppStateStore.nickname.value, announcements.get())

    private fun inject(name: String, value: Any) {
        ChatViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(viewModel, value)
    }

    private data class NicknameEffects(val persisted: String?, val state: String,
        val applicationState: String, val announcements: Int)

    private companion object {
        const val ORIGINAL = "Synthetic original nickname"
        const val RESET = "anon1234"
        const val FRESH = "Synthetic fresh profile"
    }
}
