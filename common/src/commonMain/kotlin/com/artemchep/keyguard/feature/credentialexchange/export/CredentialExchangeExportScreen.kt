package com.artemchep.keyguard.feature.credentialexchange.export

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.artemchep.keyguard.feature.auth.userverification.UserVerificationRoute
import com.artemchep.keyguard.feature.credentialexchange.CredentialExchangeItemRow
import com.artemchep.keyguard.feature.credentialexchange.credentialExchangeSkippedNotes
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabScope
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun CredentialExchangeExportScreen(
    args: CredentialExchangeExportRoute.Args,
) {
    val loadableState = produceCredentialExchangeExportScreenState(
        args = args,
    )
    val scrollBehavior = ToolbarBehavior.behavior()
    when (loadableState) {
        is Loadable.Ok -> when (val stage = loadableState.value.stage) {
            // The gate owns the whole surface, exactly as it does in the passkey
            // provider flow. The host's consent header already names the request and
            // carries the Cancel, so wrapping a centred password form in this
            // screen's own toolbar would state the title twice.
            is CredentialExchangeExportState.Stage.Locked -> {
                val onAuthenticated by rememberUpdatedState(stage.onAuthenticated)
                val route = remember {
                    UserVerificationRoute(
                        onAuthenticated = {
                            onAuthenticated()
                        },
                    )
                }
                NavigationNode(
                    id = "user_verification",
                    route = route,
                )
            }

            is CredentialExchangeExportState.Stage.Reviewable -> {
                CredentialExchangeExportScreenContent(
                    scrollBehavior = scrollBehavior,
                    stage = stage,
                )
            }
        }

        is Loadable.Loading -> {
            CredentialExchangeExportScreenSkeleton(
                scrollBehavior = scrollBehavior,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialExchangeExportScreenSkeleton(
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            CredentialExchangeExportToolbar(
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        repeat(SKELETON_ITEM_COUNT) {
            SkeletonItem()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialExchangeExportToolbar(
    scrollBehavior: TopAppBarScrollBehavior,
) {
    LargeToolbar(
        title = {
            Text(
                text = stringResource(Res.string.credential_exchange_export_header_title),
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
private fun CredentialExchangeExportScreenContent(
    scrollBehavior: TopAppBarScrollBehavior,
    stage: CredentialExchangeExportState.Stage.Reviewable,
) {
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            CredentialExchangeExportToolbar(
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = rememberCredentialExchangeExportFabState(
            stage = stage,
        ),
        floatingActionButton = {
            CredentialExchangeExportFab(
                isExporting = (stage as? CredentialExchangeExportState.Stage.Review)
                    ?.isExporting == true,
            )
        },
        listContent = {
            credentialExchangeExportStageContent(
                stage = stage,
            )
        },
    )
}

/**
 * Only the item rows are emitted lazily; every other part of a stage is bounded, so
 * it rides along in a single `item` and keeps using the `ColumnScope` composables.
 */
private fun LazyListScope.credentialExchangeExportStageContent(
    stage: CredentialExchangeExportState.Stage.Reviewable,
) {
    when (stage) {
        is CredentialExchangeExportState.Stage.Mapping -> item {
            Column {
                CredentialExchangeExportMappingContent(
                    stage = stage,
                )
            }
        }

        is CredentialExchangeExportState.Stage.Review ->
            credentialExchangeExportReviewContent(
                stage = stage,
            )

        is CredentialExchangeExportState.Stage.Unavailable -> item {
            Column {
                CredentialExchangeExportUnavailableContent(
                    stage = stage,
                )
            }
        }

        // No review chrome here: a Cancel button would make the user report an
        // internal failure to the requesting app as their own withdrawal.
        is CredentialExchangeExportState.Stage.Error -> item {
            Column {
                CredentialExchangeExportErrorContent(
                    stage = stage,
                )
            }
        }
    }
}

private fun LazyListScope.credentialExchangeExportReviewContent(
    stage: CredentialExchangeExportState.Stage.Review,
) {
    // No in-screen header sentence: the activity's own consent subtitle already
    // names both the requesting app and the source account, and rendering a vaguer
    // copy of it right underneath said the same thing twice.
    if (stage.items.isEmpty()) {
        item {
            CredentialExchangeExportEmptyLabel()
        }
    }
    items(stage.items) { item ->
        CredentialExchangeItemRow(
            item = item,
        )
    }
    credentialExchangeSkippedNotes(
        notes = stage.skipped,
    )
}

@Composable
private fun rememberCredentialExchangeExportFabState(
    stage: CredentialExchangeExportState.Stage.Reviewable,
): State<FabState?> {
    val fabState = when (stage) {
        // The confirm action belongs to the review, and there is nothing to
        // review until the mapping is done.
        is CredentialExchangeExportState.Stage.Mapping -> null

        is CredentialExchangeExportState.Stage.Unavailable -> null

        // While the host builds the response, keep the button visible but
        // disabled so the user gets feedback instead of a dead-looking tap.
        is CredentialExchangeExportState.Stage.Review ->
            if (stage.onConfirm != null || stage.isExporting) {
                FabState(
                    onClick = stage.onConfirm,
                    model = null,
                )
            } else {
                null
            }

        is CredentialExchangeExportState.Stage.Error -> null
    }
    return rememberUpdatedState(newValue = fabState)
}

@Composable
private fun FabScope.CredentialExchangeExportFab(
    isExporting: Boolean,
) {
    DefaultFab(
        icon = {
            Crossfade(
                modifier = Modifier
                    .size(24.dp),
                targetState = isExporting,
            ) { exporting ->
                if (exporting) {
                    KeyguardLoadingIndicator()
                } else {
                    Icon(Icons.Outlined.IosShare, null)
                }
            }
        },
        text = {
            Text(
                text = stringResource(Res.string.credential_exchange_export_export_button),
            )
        },
    )
}

@Composable
private fun CredentialExchangeExportEmptyLabel() {
    Text(
        modifier = Modifier
            .padding(
                horizontal = Dimens.textHorizontalPadding,
                vertical = 16.dp,
            ),
        text = stringResource(Res.string.credential_exchange_export_empty_label),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
internal fun ColumnScope.CredentialExchangeExportCancelButton(
    onDeny: (() -> Unit)?,
) {
    if (onDeny == null) {
        return
    }
    val updatedOnDeny by rememberUpdatedState(onDeny)
    TextButton(
        modifier = Modifier
            .padding(horizontal = Dimens.buttonHorizontalPadding),
        onClick = {
            updatedOnDeny.invoke()
        },
    ) {
        Text(
            text = stringResource(Res.string.cancel),
        )
    }
}

/**
 * Shared with the mapping stage, which shows the very same rows so that passing the
 * gate does not swap one kind of waiting for a different-looking one.
 */
internal const val SKELETON_ITEM_COUNT = 3
