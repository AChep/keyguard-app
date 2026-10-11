package com.artemchep.keyguard.feature.gpgagent.tools

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.artemchep.keyguard.ui.FlatItemTextContent

@Composable
internal fun ColumnScope.GpgToolsKeyIdentity(
    key: GpgToolsState.KeyItem,
) {
    FlatItemTextContent(
        title = {
            Text(key.title)
        },
        text = key.description?.takeIf(String::isNotBlank)?.let { fingerprint ->
            {
                Text(fingerprint, fontFamily = FontFamily.Monospace)
            }
        },
        compact = true,
    )
}
