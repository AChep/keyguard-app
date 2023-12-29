package com.artemchep.keyguard.ui.skeleton

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.ui.theme.Dimens

@Composable
fun SkeletonSection(
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.33f,
) {
    SkeletonText(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .padding(
                vertical = 16.dp,
                horizontal = Dimens.horizontalPadding,
            ),
        style = MaterialTheme.typography.labelLarge
            .copy(fontSize = 12.sp),
    )
}
