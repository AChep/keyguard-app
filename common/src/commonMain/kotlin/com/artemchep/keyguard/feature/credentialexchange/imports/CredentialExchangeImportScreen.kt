package com.artemchep.keyguard.feature.credentialexchange.imports

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AnimatedTotalCounterBadge
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabScope
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

internal const val IMPORT_SKELETON_ITEM_COUNT = 3

@Composable
fun CredentialExchangeImportScreen(
    args: CredentialExchangeImportRoute.Args,
) {
    val loadableState = produceCredentialExchangeImportScreenState(
        args = args,
    )
    val scrollBehavior = ToolbarBehavior.behavior()
    when (loadableState) {
        is Loadable.Ok -> {
            CredentialExchangeImportScreenContent(
                scrollBehavior = scrollBehavior,
                state = loadableState.value,
            )
        }

        is Loadable.Loading -> {
            CredentialExchangeImportScreenSkeleton(
                scrollBehavior = scrollBehavior,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialExchangeImportScreenSkeleton(
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            CredentialExchangeImportToolbar(
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
private fun CredentialExchangeImportToolbar(
    scrollBehavior: TopAppBarScrollBehavior,
) {
    LargeToolbar(
        title = {
            Text(
                text = stringResource(Res.string.credential_exchange_import_header_title),
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
private fun CredentialExchangeImportScreenContent(
    scrollBehavior: TopAppBarScrollBehavior,
    state: CredentialExchangeImportState,
) {
    val context by rememberUpdatedState(LocalLeContext)
    val stage = state.stage
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            CredentialExchangeImportToolbar(
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = rememberCredentialExchangeImportFabState(
            stage = stage,
            onStart = {
                val start = stage as? CredentialExchangeImportState.Stage.Start
                start?.onImport?.invoke(context)
            },
            onRetry = {
                val error = stage as? CredentialExchangeImportState.Stage.Error
                error?.onRetry?.invoke(context)
            },
        ),
        floatingActionButton = {
            CredentialExchangeImportFab(
                stage = stage,
            )
        },
    ) {
        credentialExchangeImportStageContent(
            stage = stage,
        )
    }
}

@Composable
internal fun ColumnScope.CredentialExchangeImportStartContent(
    stage: CredentialExchangeImportState.Stage.Start,
) {
    Text(
        modifier = Modifier
            .padding(
                horizontal = Dimens.textHorizontalPadding,
                vertical = 8.dp,
            ),
        text = stringResource(Res.string.credential_exchange_import_header_text),
        style = MaterialTheme.typography.bodyLarge,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        CredentialExchangeImportStep(
            number = 1,
            title = stringResource(Res.string.credential_exchange_import_step_1_title),
            text = stringResource(Res.string.credential_exchange_import_step_1_text),
        )
        CredentialExchangeImportStep(
            number = 2,
            title = stringResource(Res.string.credential_exchange_import_step_2_title),
            text = stringResource(Res.string.credential_exchange_import_step_2_text),
        )
        CredentialExchangeImportStep(
            number = 3,
            title = stringResource(Res.string.credential_exchange_import_step_3_title),
            text = stringResource(Res.string.credential_exchange_import_step_3_text),
        )
    }
    stage.onLearnMore?.let { onLearnMore ->
        val updatedOnLearnMore by rememberUpdatedState(onLearnMore)
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        TextButton(
            modifier = Modifier
                .padding(
                    horizontal = Dimens.buttonHorizontalPadding,
                ),
            onClick = updatedOnLearnMore,
        ) {
            Icon(
                modifier = Modifier
                    .size(18.dp),
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
            )
            Spacer(
                modifier = Modifier
                    .width(Dimens.buttonIconPadding),
            )
            Text(
                text = stringResource(Res.string.credential_exchange_import_learn_more),
            )
        }
    }
}

@Composable
private fun CredentialExchangeImportStep(
    number: Int,
    title: String,
    text: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.textHorizontalPadding,
                vertical = 10.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar {
            Text(
                text = number.toString(),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(
                modifier = Modifier
                    .height(2.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current
                    .combineAlpha(alpha = MediumEmphasisAlpha),
            )
        }
    }
}

@Composable
internal fun ColumnScope.CredentialExchangeImportLoadingContent() {
    repeat(IMPORT_SKELETON_ITEM_COUNT) {
        SkeletonItem()
    }
}

private fun LazyListScope.credentialExchangeImportStageContent(
    stage: CredentialExchangeImportState.Stage,
) {
    when (stage) {
        is CredentialExchangeImportState.Stage.Start -> item {
            Column {
                CredentialExchangeImportStartContent(
                    stage = stage,
                )
            }
        }

        is CredentialExchangeImportState.Stage.Loading -> item {
            Column {
                CredentialExchangeImportLoadingContent()
            }
        }

        is CredentialExchangeImportState.Stage.Review ->
            credentialExchangeImportReviewContent(
                stage = stage,
            )

        is CredentialExchangeImportState.Stage.Empty ->
            credentialExchangeImportEmptyContent(
                stage = stage,
            )

        is CredentialExchangeImportState.Stage.Done -> item {
            Column {
                CredentialExchangeImportDoneContent(
                    stage = stage,
                )
            }
        }

        is CredentialExchangeImportState.Stage.Error -> item {
            Column {
                CredentialExchangeImportErrorContent(
                    stage = stage,
                )
            }
        }
    }
}

@Composable
private fun rememberCredentialExchangeImportFabState(
    stage: CredentialExchangeImportState.Stage,
    onStart: () -> Unit,
    onRetry: () -> Unit,
): State<FabState?> {
    val fabState = when (stage) {
        is CredentialExchangeImportState.Stage.Start ->
            stage.onImport?.let {
                FabState(
                    onClick = onStart,
                    model = null,
                )
            }

        is CredentialExchangeImportState.Stage.Review ->
            if (stage.onConfirm != null || stage.isImporting) {
                FabState(
                    onClick = stage.onConfirm,
                    model = null,
                )
            } else {
                null
            }

        is CredentialExchangeImportState.Stage.Error ->
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
private fun FabScope.CredentialExchangeImportFab(
    stage: CredentialExchangeImportState.Stage,
) {
    val isImporting = (stage as? CredentialExchangeImportState.Stage.Review)
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
                        is CredentialExchangeImportState.Stage.Review -> {
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

                        is CredentialExchangeImportState.Stage.Error ->
                            Icon(Icons.Outlined.Refresh, null)

                        else -> Icon(Icons.Outlined.AppShortcut, null)
                    }
                }
            }
        },
        text = {
            val res = when (stage) {
                is CredentialExchangeImportState.Stage.Review ->
                    Res.string.credential_exchange_import_import_button

                is CredentialExchangeImportState.Stage.Error ->
                    Res.string.credential_exchange_import_retry_button

                else -> Res.string.credential_exchange_import_start_button
            }
            Text(
                text = stringResource(res),
            )
        },
    )
}
