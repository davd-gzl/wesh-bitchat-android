package com.bitchat.android.ui.pave

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R

/**
 * A remote nickname ready to display, through [PaveDisplayName.remote] with the central
 * [PaveReservedWords]. Every Pavé component that shows someone else's name goes through this.
 */
@Composable
fun remoteDisplayName(raw: String?): String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val reservedWords = remember(context, configuration) { PaveReservedWords.words(context) }
    return PaveDisplayName.remote(
        raw,
        unnamed = stringResource(R.string.wesh_pave_unnamed),
        reserved = stringResource(R.string.wesh_pave_unnamed_reserved),
        reservedWords = reservedWords,
    )
}
