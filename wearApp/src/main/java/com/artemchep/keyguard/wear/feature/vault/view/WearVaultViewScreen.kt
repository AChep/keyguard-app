package com.artemchep.keyguard.wear.feature.vault.view

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewState
import com.artemchep.keyguard.feature.home.vault.screen.toVaultViewHeaderState
import com.artemchep.keyguard.feature.home.vault.screen.vaultViewScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.items_empty_label
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionallyKeepScreenOnEffect
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.wear.feature.vault.component.WearVaultViewItem
import com.artemchep.keyguard.wear.ui.WearListEmpty
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.WearViewHeader
import com.artemchep.keyguard.wear.ui.wearViewLoadingSkeletonItems
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearVaultViewScreen(
    itemId: String,
    accountId: String,
) {
    OptionallyKeepScreenOnEffect()

    val state = vaultViewScreenState(
        mode = LocalAppMode.current,
        contentColor = LocalContentColor.current,
        disabledContentColor = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
        itemId = itemId,
        accountId = accountId,
    )
    WearVaultViewScaffold(
        state = state,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WearVaultViewScaffold(
    state: VaultViewState,
) {
    WearScaffoldScreen(
        header = { transformationSpec ->
            WearVaultViewHeader(
                modifier = Modifier
                    .transformedHeight(this, transformationSpec),
                state = state,
                transformation = SurfaceTransformation(transformationSpec),
            )
        },
    ) { transformationSpec ->
        when (val content = state.content) {
            is VaultViewState.Content.Loading -> {
                wearViewLoadingSkeletonItems(
                    transformationSpec = transformationSpec,
                )
            }

            is VaultViewState.Content.Cipher -> {
                val list = content.items
                if (list.isEmpty()) {
                    item("header.empty") {
                        WearListEmpty(
                            modifier = Modifier
                                .transformedHeight(this, transformationSpec),
                            text = stringResource(Res.string.items_empty_label),
                            transformation = SurfaceTransformation(transformationSpec),
                        )
                    }
                }
                items(
                    items = list,
                    key = { model -> model.id },
                ) { model ->
                    WearVaultViewItem(
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec),
                        item = model,
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }
            }

            else -> {
                // Do nothing.
            }
        }
    }
}

@Composable
fun WearVaultViewHeader(
    modifier: Modifier = Modifier,
    state: VaultViewState,
    transformation: SurfaceTransformation? = null,
) {
    val header = state.toVaultViewHeaderState()
    WearViewHeader(
        modifier = modifier,
        isLoading = header.isLoading,
        icon = header.icon,
        name = header.name,
        accentLight = header.accentLight,
        accentDark = header.accentDark,
        hasRemoteService = header.hasRemoteService,
        transformation = transformation,
    )
}
