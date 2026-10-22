package com.bitchat.android.ui

import android.app.Application
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The view model's Internet notifier relies on the default Main dispatcher, as wired in ChatViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WeshInternetNotifierWiringTest {
    private val mainThread = AtomicReference<Thread>()
    private val main = Executors.newSingleThreadExecutor { Thread(it).also(mainThread::set) }
    private val scope = CoroutineScope(SupervisorJob())

    @Before fun setUp() = Dispatchers.setMain(main.asCoroutineDispatcher())

    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
        main.shutdown()
    }

    @Test fun `the view model's Internet notifier shows on Main, not on the delivering thread, and not after an erase`() {
        val shown = LinkedBlockingQueue<Pair<String, Thread>>()
        val notifications = mock<NotificationManager>()
        doAnswer { shown += (it.arguments[0] as String) to Thread.currentThread(); null }
            .whenever(notifications).showPrivateMessageNotification(any(), anyOrNull(), any())
        val notifier = ChatViewModel.internetNotifierOnMain(scope, notifications)
        val message = BitchatMessage(id = "m-1", sender = "Alice", content = "salut", timestamp = Date(),
            isPrivate = true, senderPeerID = "synthetic-alice")

        notifier("synthetic-alice", message) { false }
        notifier("synthetic-alice", message.copy(id = "m-2")) { true }

        assertEquals("synthetic-alice" to mainThread.get(), shown.poll(5, TimeUnit.SECONDS))
        assertNull(shown.poll(200, TimeUnit.MILLISECONDS))
        verify(notifications, times(1)).showPrivateMessageNotification(any(), anyOrNull(), any())
    }
}
