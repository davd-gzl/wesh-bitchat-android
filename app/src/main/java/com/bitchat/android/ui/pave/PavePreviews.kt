package com.bitchat.android.ui.pave

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/** Every Pavé component is previewed in Nuit and Jour, at normal size and at font scale 2.0. */
@Preview(name = "Nuit", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0A0A0A)
@Preview(name = "Jour", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFF5F5F0)
@Preview(name = "Nuit, texte 200 %", uiMode = Configuration.UI_MODE_NIGHT_YES, fontScale = 2f, showBackground = true, backgroundColor = 0xFF0A0A0A)
@Preview(name = "Jour, texte 200 %", uiMode = Configuration.UI_MODE_NIGHT_NO, fontScale = 2f, showBackground = true, backgroundColor = 0xFFF5F5F0)
annotation class PavePreviews

/** Full-screen variant (390 x 844, like the mockups). */
@Preview(name = "Nuit", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 390, heightDp = 844)
@Preview(name = "Jour", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 390, heightDp = 844)
@Preview(name = "Nuit, texte 200 %", uiMode = Configuration.UI_MODE_NIGHT_YES, fontScale = 2f, widthDp = 390, heightDp = 844)
@Preview(name = "Jour, texte 200 %", uiMode = Configuration.UI_MODE_NIGHT_NO, fontScale = 2f, widthDp = 390, heightDp = 844)
annotation class PaveScreenPreviews

/** Wraps a component preview in the Pavé theme matching the preview's night mode. */
@Composable
internal fun PavePreview(padded: Boolean = true, content: @Composable () -> Unit) {
    WeshPaveTheme(WeshAppearance.Automatique.resolve()) {
        val base = Modifier.background(WeshPave.colors.bg)
        Box(if (padded) base.padding(WeshSpace.gutter) else base) { content() }
    }
}
