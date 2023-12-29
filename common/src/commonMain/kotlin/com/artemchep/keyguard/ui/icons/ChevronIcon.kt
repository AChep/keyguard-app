package com.artemchep.keyguard.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun ChevronIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
        .combineAlpha(MediumEmphasisAlpha),
) {
    Icon(
        modifier = modifier,
        imageVector = Icons.Outlined.ChevronRight,
        contentDescription = null,
        tint = tint,
    )
}
