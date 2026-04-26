package com.artemchep.keyguard.wear.feature.vault

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.wear.ui.WearSectionHeader

@Composable
internal fun WearVaultRouteSectionItem(
    modifier: Modifier = Modifier,
    text: String?,
    transformation: SurfaceTransformation? = null,
) {
    WearSectionHeader(
        modifier = modifier,
        title = text,
        transformation = transformation,
    )
}

@Composable
internal fun WearVaultRouteListItem(
    modifier: Modifier = Modifier,
    title: String,
    text: String? = null,
    onClick: (() -> Unit)? = null,
    transformation: SurfaceTransformation? = null,
) {
    val updatedOnClick = rememberUpdatedState(onClick)
    FilledTonalButton(
        modifier = modifier
            .fillMaxWidth(),
        label = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        secondaryLabel = text
            ?.takeIf { it.isNotBlank() }
            ?.let { value ->
                {
                    Text(
                        text = value,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        onClick = {
            updatedOnClick.value?.invoke()
        },
        enabled = onClick != null,
        transformation = transformation,
    )
}
