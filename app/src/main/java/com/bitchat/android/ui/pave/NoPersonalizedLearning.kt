package com.bitchat.android.ui.pave

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Asks the keyboard not to learn from what is typed: no personalized dictionary, no typing history
 * kept or synced by the keyboard app. Every Wesh text field is wrapped in this (WeshTextFieldPrivacyTest).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val private = object : PlatformTextInputMethodRequest by request {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    outAttributes.imeOptions = withNoPersonalizedLearning(outAttributes.imeOptions)
                    return connection
                }
            }
            nextHandler.startInputMethod(private)
        },
        content = content,
    )
}

internal fun withNoPersonalizedLearning(imeOptions: Int): Int =
    imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
