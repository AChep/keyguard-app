package com.artemchep.keyguard.feature.imports

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AnimatedTotalCounterBadge
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabScope
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

internal const val IMPORT_SKELETON_ITEM_COUNT = 3

@Composable
fun ImportScreen(
    args: ImportRoute.Args,
) {
    val loadableState = produceImportScreenState(
        args = args,
    )
    val scrollBehavior = ToolbarBehavior.behavior()
    when (loadableState) {
        is Loadable.Ok -> {
            ImportScreenContent(
                scrollBehavior = scrollBehavior,
                state = loadableState.value,
            )
        }

        is Loadable.Loading -> {
            ImportScreenSkeleton(
                scrollBehavior = scrollBehavior,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportScreenSkeleton(
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            ImportToolbar(
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        repeat(IMPORT_SKELETON_ITEM_COUNT) {
            SkeletonItem()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportToolbar(
    scrollBehavior: TopAppBarScrollBehavior,
) {
    LargeToolbar(
        title = {
            Text(
                text = stringResource(Res.string.import_header_title),
            )
        },
        navigationIcon = {
            NavigationIcon()
        },
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportScreenContent(
    scrollBehavior: TopAppBarScrollBehavior,
    state: ImportState,
) {
    val context by rememberUpdatedState(LocalLeContext)
    val stage = state.stage
    FilePickerEffect(state.sideEffects.filePickerIntentFlow)
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            ImportToolbar(
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = rememberImportFabState(
            stage = stage,
            onRetry = {
                val error = stage as? ImportState.Stage.Error
                error?.onRetry?.invoke(context)
            },
        ),
        floatingActionButton = {
            ImportFab(
                stage = stage,
            )
        },
    ) {
        importStageContent(
            stage = stage,
        )
    }
}

@Composable
internal fun ColumnScope.ImportLoadingContent() {
    repeat(IMPORT_SKELETON_ITEM_COUNT) {
        SkeletonItem()
    }
}

private fun LazyListScope.importStageContent(
    stage: ImportState.Stage,
) {
    when (stage) {
        is ImportState.Stage.Start ->
            importStartContent(
                stage = stage,
            )

        is ImportState.Stage.Password -> item("password") {
            Column {
                ImportPasswordContent(
                    stage = stage,
                )
            }
        }

        is ImportState.Stage.Snapshots ->
            importSnapshotsContent(
                stage = stage,
            )

        is ImportState.Stage.Loading -> item {
            Column {
                ImportLoadingContent()
            }
        }

        is ImportState.Stage.Review ->
            importReviewContent(
                stage = stage,
            )

        is ImportState.Stage.Empty ->
            importEmptyContent(
                stage = stage,
            )

        is ImportState.Stage.Done -> item {
            Column {
                ImportDoneContent(
                    stage = stage,
                )
            }
        }

        is ImportState.Stage.Error -> item {
            Column {
                ImportErrorContent(
                    stage = stage,
                )
            }
        }
    }
}

@Composable
private fun rememberImportFabState(
    stage: ImportState.Stage,
    onRetry: () -> Unit,
): State<FabState?> {
    val fabState = when (stage) {
        is ImportState.Stage.Review ->
            if (stage.onConfirm != null || stage.isImporting) {
                FabState(
                    onClick = stage.onConfirm,
                    model = null,
                )
            } else {
                null
            }

        is ImportState.Stage.Password ->
            FabState(
                onClick = stage.onConfirm,
                model = null,
            )

        is ImportState.Stage.Snapshots ->
            FabState(
                onClick = stage.onConfirm,
                model = null,
            )

        is ImportState.Stage.Error ->
            stage.onRetry?.let {
                FabState(
                    onClick = onRetry,
                    model = null,
                )
            }

        else -> null
    }
    return rememberUpdatedState(newValue = fabState)
}

@Composable
private fun FabScope.ImportFab(
    stage: ImportState.Stage,
) {
    val isImporting = (stage as? ImportState.Stage.Review)
        ?.isImporting == true
    DefaultFab(
        icon = {
            Crossfade(
                modifier = Modifier
                    .size(24.dp),
                targetState = isImporting,
            ) { importing ->
                if (importing) {
                    KeyguardLoadingIndicator()
                } else {
                    when (stage) {
                        is ImportState.Stage.Review -> {
                            BadgedBox(
                                badge = {
                                    val count = stage.items
                                        .count { it.selected }
                                    AnimatedTotalCounterBadge(
                                        count = count,
                                    )
                                },
                            ) {
                                Icon(Icons.Outlined.SystemUpdateAlt, null)
                            }
                        }

                        is ImportState.Stage.Password ->
                            Icon(Icons.Outlined.LockOpen, null)

                        is ImportState.Stage.Snapshots ->
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)

                        else -> Icon(Icons.Outlined.Refresh, null)
                    }
                }
            }
        },
        text = {
            val res = when (stage) {
                is ImportState.Stage.Review ->
                    Res.string.import_confirm_button

                is ImportState.Stage.Password,
                is ImportState.Stage.Snapshots,
                -> Res.string.continue_

                else -> Res.string.import_retry_button
            }
            Text(
                text = stringResource(res),
            )
        },
    )
}
