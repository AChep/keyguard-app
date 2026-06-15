package com.artemchep.keyguard.wear.feature.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.wear.ui.WearListEmpty
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.skeletonItems

/**
 * Generic scaffold for category-list screens (collections, folders, organizations).
 *
 * All three share the same structure:
 * - Loading skeleton
 * - Empty label when the list has no items
 * - Keyed list of items rendered via [itemContent]
 */
@Composable
fun <I : Any> WearCategoryListScreen(
    icon: ImageVector,
    title: String,
    emptyLabel: String,
    content: Loadable<List<I>>,
    itemKey: (I) -> Any,
    itemContent: @Composable (I, Modifier, SurfaceTransformation?) -> Unit,
) {
    WearScaffoldScreen(
        icon = icon,
        title = title,
    ) { transformationSpec ->
        when (content) {
            is Loadable.Loading -> {
                skeletonItems(
                    transformationSpec = transformationSpec,
                    count = 3,
                )
            }

            is Loadable.Ok -> {
                val items = content.value
                if (items.isEmpty()) {
                    item("empty") {
                        WearListEmpty(
                            modifier = Modifier
                                .transformedHeight(this, transformationSpec),
                            text = emptyLabel,
                            transformation = SurfaceTransformation(transformationSpec),
                        )
                    }
                }
                items(
                    items = items,
                    key = { item -> itemKey(item) },
                ) { item ->
                    itemContent(
                        item,
                        Modifier.transformedHeight(this, transformationSpec),
                        SurfaceTransformation(transformationSpec),
                    )
                }
            }
        }
    }
}
