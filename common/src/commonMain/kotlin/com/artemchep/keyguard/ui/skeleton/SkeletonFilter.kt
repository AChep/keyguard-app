package com.artemchep.keyguard.ui.skeleton

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.search.filter.component.FilterItemLayout

@Composable
fun SkeletonFilter(
    modifier: Modifier = Modifier,
) {
    FilterItemLayout(
        modifier = modifier,
        checked = false,
        leading = null,
        content = {
            SkeletonText(
                modifier = Modifier
                    .width(64.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        },
        onClick = null,
        enabled = true,
    )
}
