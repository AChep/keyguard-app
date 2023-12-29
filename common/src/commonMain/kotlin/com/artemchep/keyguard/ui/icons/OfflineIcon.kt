package com.artemchep.keyguard.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.ui.shimmer.shimmer

@Composable
fun OfflineIcon(
    modifier: Modifier = Modifier,
) {
    Icon(
        modifier = modifier
            .shimmer(),
        imageVector = Icons.Outlined.CloudUpload,
        contentDescription = null,
    )
}
