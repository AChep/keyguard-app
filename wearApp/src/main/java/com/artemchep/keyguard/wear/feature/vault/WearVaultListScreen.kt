package com.artemchep.keyguard.wear.feature.vault

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.LocalNavigationEntry
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.ui.skeleton.SkeletonItemAvatar
import com.artemchep.keyguard.wear.feature.vault.view.WearVaultViewRoute
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.skeletonItems
import org.jetbrains.compose.resources.stringResource
import kotlin.collections.orEmpty

@Composable
fun WearVaultListScreen(
    args: VaultRoute.Args = VaultRoute.Args(),
) {
    val state = wearVaultListScreenState(
        args = args,
    )

    val xd by rememberUpdatedState(LocalNavigationEntry.current.id)
    val controller by rememberUpdatedState(LocalNavigationController.current)
    CollectedEffect(state.sideEffects.showBiometricPromptFlow) {
        val route = WearVaultViewRoute(
            itemId = it.id,
            accountId = it.accountId,
        )
        val intent = NavigationIntent.Composite(
            listOf(
                NavigationIntent.PopById(xd),
                NavigationIntent.NavigateToRoute(route),
            ),
        )
        controller.queue(intent)
//        bs.push(route)
    }

    WearScaffoldScreen(
        title = args.appBar?.title,
    ) { transformationSpec ->
        when (state.content) {
            is WearVaultListState.Content.Skeleton -> {
                // Show a bunch of skeleton items, so it makes an impression of a
                // fully loaded screen.
                skeletonItems(
                    transformationSpec = transformationSpec,
                    count = 12,
                )
            }

            else -> {
                if (state.content is WearVaultListState.Content.AddAccount) {
                    item("header.add_account") {
                        WearNoAccounts(
                            modifier = Modifier
                                .transformedHeight(this, transformationSpec),
                        )
                    }
                }

                val list = (state.content as? WearVaultListState.Content.Items)?.list.orEmpty()
                items(
                    items = list,
                    key = { model -> model.id },
                ) { model ->
                    if (model is VaultItem2.QuickFilters) {
                        // Do nothing
                    } else {
                        WearVaultListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            item = model,
                            transformation = SurfaceTransformation(transformationSpec),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WearNoAccounts(
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = stringResource(Res.string.addaccount_promo_title_no_direct_action),
        textAlign = TextAlign.Center,
    )
}
