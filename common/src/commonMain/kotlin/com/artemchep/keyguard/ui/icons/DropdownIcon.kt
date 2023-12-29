package com.artemchep.keyguard.ui.icons

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun DropdownIcon(
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    tint: Color = LocalContentColor.current
        .combineAlpha(MediumEmphasisAlpha),
) {
    val iconRotationTarget = if (expanded) 180f else 0f
    val iconRotation by animateFloatAsState(iconRotationTarget)
    Icon(
        modifier = modifier
            .rotate(iconRotation),
        imageVector = Icons.Outlined.ArrowDropDown,
        contentDescription = null,
        tint = tint,
    )
}
