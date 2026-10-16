package com.bitchat.android.ui.pave

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import java.util.Locale

/** 48 dp icon-only button; [label] is mandatory so TalkBack can name it. */
@Composable
fun PaveIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(WeshTouch.min).paveClickable(CircleShape, onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = WeshPave.colors.ink, modifier = Modifier.size(24.dp))
    }
}

/** Screen title row: optional back, a poster `display` heading in capitals (read as typed), optional close or trailing content. */
@Composable
fun PaveHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WeshSpace.s2),
        modifier = modifier.fillMaxWidth().heightIn(min = WeshTouch.primary).padding(vertical = WeshSpace.s2),
    ) {
        if (onBack != null) PaveIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.wesh_pave_back), onBack)
        Text(
            title.uppercase(Locale.ROOT),
            style = WeshType.display,
            color = WeshPave.colors.ink,
            modifier = Modifier.weight(1f).semantics { heading(); contentDescription = title },
        )
        trailing?.invoke()
        if (onClose != null) PaveIconButton(Icons.Rounded.Close, stringResource(R.string.wesh_pave_close), onClose)
    }
}
