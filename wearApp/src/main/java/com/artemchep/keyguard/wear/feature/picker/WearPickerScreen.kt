package com.artemchep.keyguard.wear.feature.picker

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.wear.ui.ProxyMaterial3Styles
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.WearSectionHeader

@Composable
fun WearPickerScreen(
    items: List<ContextItem>,
) {
    val navigationController = LocalNavigationController.current
    val onDismiss = remember(navigationController) {
        // dismiss the screen
        {
            val intent = NavigationIntent.Pop
            navigationController.queue(intent)
        }
    }
    WearScaffoldScreen(
        title = "Keyguard",
    ) { transformationSpec ->
        itemsIndexed(
            items,
            key = { index, _ -> index },
        ) { _, item ->
            this.WearPickerItem(
                item = item,
                onDismiss = onDismiss,
                transformationSpec = transformationSpec,
            )
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.WearPickerItem(
    item: ContextItem,
    onDismiss: () -> Unit,
    transformationSpec: TransformationSpec,
) {
    when (item) {
        is ContextItem.Section -> {
            WearSectionHeader(
                modifier = Modifier
                    .transformedHeight(this, transformationSpec),
                title = item.title,
                transformation = SurfaceTransformation(transformationSpec),
            )
        }

        is ContextItem.Custom -> {
            // Do nothing
        }

        is FlatItemAction -> {
            val updatedOnClick by rememberUpdatedState(item)
            FilledTonalButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                icon = item.icon
                    ?.let { icon ->
                        // composable
                        {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                            )
                        }
                    }
                    ?: item.leading?.let { content ->
                        // composable
                        {
                            ProxyMaterial3Styles {
                                content()
                            }
                        }
                    },
                label = {
                    Text(
                        text = textResource(item.title),
                    )
                },
                secondaryLabel = if (item.text != null) {
                    // composable
                    {
                        Text(
                            text = textResource(item.text)
                                .orEmpty(),
                        )
                    }
                } else {
                    null
                },
                onClick = {
                    onDismiss()
                    updatedOnClick.onClick?.invoke()
                },
                transformation = SurfaceTransformation(transformationSpec),
            )
        }
    }
}
