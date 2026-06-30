package com.artemchep.keyguard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun HtmlText(
    modifier: Modifier = Modifier,
    html: String,
)
