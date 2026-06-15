package com.artemchep.keyguard.wear.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemArgs
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.feature.home.settings.SettingPaneState
import com.artemchep.keyguard.feature.home.settings.rememberSettingPaneState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.items_empty_label
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.wear.ui.WearListEmpty
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearSettingsPaneScaffold(
    title: String,
    items: List<SettingPaneItem>,
) {
    val panelState = rememberSettingPaneState(items)
    WearSettingsPaneScaffold(
        title = title,
        state = panelState.value,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun WearSettingsPaneScaffold(
    title: String,
    state: SettingPaneState,
) {
    val components = remember {
        WearSettingsComponents()
    }
    CompositionLocalProvider(
        LocalSettingPaneComponents provides components,
    ) {
        WearScaffoldScreen(
            title = title,
        ) { transformationSpec ->
            when (val contentState = state.list) {
                is Loadable.Loading -> {
                    item("skeleton") {
                        Box(
                            modifier = Modifier
                                .transformedHeight(this, transformationSpec),
                        ) {
                            SkeletonItem()
                        }
                    }
                }

                is Loadable.Ok -> {
                    val list = contentState.value
                    if (list.isEmpty()) {
                        item("empty") {
                            WearListEmpty(
                                modifier = Modifier
                                    .transformedHeight(this, transformationSpec),
                                text = stringResource(Res.string.items_empty_label),
                                transformation = SurfaceTransformation(transformationSpec),
                            )
                        }
                    }

                    itemsIndexed(
                        items = list,
                        key = { _, model -> model.compositeKey },
                    ) { index, model ->
                        val shapeState = getShapeState(
                            list,
                            index,
                            predicate = { item, _ ->
                                item.groupKey == model.groupKey &&
                                        item.itemKey != "divider"
                            },
                        )
                        CompositionLocalProvider(
                            LocalSettingItemArgs provides model.args,
                            LocalSettingItemShape provides shapeState,
                            LocalWearSettingsTransformation provides SurfaceTransformation(transformationSpec),
                        ) {
                            Box(
                                modifier = Modifier
                                    .transformedHeight(this, transformationSpec),
                            ) {
                                model.content?.invoke()
                            }
                        }
                    }
                }
            }
        }
    }
}
