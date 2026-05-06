package com.artemchep.keyguard.wear.feature.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.WearSectionHeader

@Composable
fun WearHomeScreen() {
    val state = wearHomeScreenState()
    WearScaffoldScreen(
        header = state.headerItem?.let { item ->
            { transformationSpec ->
                ListHeader(
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    CompactButton(
                        onClick = item.onClick,
                    ) {
                        item.icon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = textResource(item.title),
                            )
                        }
                    }
                }
            }
        },
    ) { transformationSpec ->
        items(
            items = state.items,
            key = { it.id },
            contentType = { it.contentType },
        ) {
            when (it) {
                is WearHomeState.Item.Action -> {
                    HomeListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        item = it,
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }

                is WearHomeState.Item.Section -> {
                    WearSectionHeader(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        title = textResource(it.title),
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }
            }
        }
    }
}

@Composable
fun HomeListItem(
    modifier: Modifier = Modifier,
    item: WearHomeState.Item.Action,
    transformation: SurfaceTransformation? = null,
) {
    FilledTonalButton(
        modifier = modifier,
        onClick = item.onClick,
        label = {
            Text(
                text = textResource(item.title),
            )
        },
        icon = if (item.icon != null) {
            // composable
            {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                )
            }
        } else {
            null
        },
        transformation = transformation,
    )
}
