package com.bitchat.android.ui

import androidx.compose.runtime.Composable
import com.bitchat.android.ui.pave.WeshPaveApp

/** Wesh's main surface once onboarding is done: the Pavé Fluo screens. */
@Composable
fun WeshScreen(viewModel: ChatViewModel) = WeshPaveApp(viewModel)
