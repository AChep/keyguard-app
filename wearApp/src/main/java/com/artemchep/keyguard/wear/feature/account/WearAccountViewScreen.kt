package com.artemchep.keyguard.wear.feature.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.auth.AccountViewState
import com.artemchep.keyguard.feature.auth.accountState
import com.artemchep.keyguard.feature.auth.actions
import com.artemchep.keyguard.feature.auth.content
import com.artemchep.keyguard.feature.auth.data
import com.artemchep.keyguard.feature.auth.items
import com.artemchep.keyguard.feature.auth.primaryAction
import com.artemchep.keyguard.feature.auth.rememberAccountViewFabState
import com.artemchep.keyguard.feature.auth.toAccountViewHeaderState
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.BetaBadge
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionallyKeepScreenOnEffect
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.wear.feature.vault.component.WearVaultViewItem
import com.artemchep.keyguard.wear.ui.DefaultEdgeButton
import com.artemchep.keyguard.wear.ui.WearContextAction
import com.artemchep.keyguard.wear.ui.WearDotsDivider
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import org.jetbrains.compose.resources.painterResource

@Composable
fun AccountViewScreen(
    accountId: AccountId,
) {
    OptionallyKeepScreenOnEffect()

    val state = accountState(accountId)
    AccountViewScaffold(
        state = state,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
)
@Composable
fun AccountViewScaffold(
    state: AccountViewState,
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
        floatingActionState = rememberAccountViewFabState(state),
        floatingActionButton = {
            val primaryAction = AccountViewState.content.data.primaryAction.getOrNull(state)
            DefaultEdgeButton(
                icon = {
                    val icon = primaryAction?.icon
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                        )
                    }
                },
                text = {
                    Text(primaryAction?.text.orEmpty())
                },
            )
        },
    ) { transformationSpec ->
        val items = AccountViewState.content.data.items
            .getOrNull(state)
            .orEmpty()
        items(
            items,
            key = { it.id },
        ) {
            WearVaultViewItem(
                modifier = Modifier
                    .transformedHeight(this, transformationSpec),
                item = it,
                transformation = SurfaceTransformation(transformationSpec),
            )
        }

        val actions = AccountViewState.content.data.actions
            .getOrNull(state)
            .orEmpty()
        if (actions.isNotEmpty()) {
            item("action.section") {
                WearDotsDivider(
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
        }
        itemsIndexed(
            actions,
            key = { i, _ -> "action.$i" },
        ) { i, action ->
            WearContextAction(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                item = action,
                transformation = SurfaceTransformation(transformationSpec),
            )
        }
    }
}

@Composable
fun WearVaultViewHeader(
    modifier: Modifier = Modifier,
    state: AccountViewState,
    transformation: SurfaceTransformation? = null,
) {
    ListHeader(
        modifier = modifier,
        transformation = transformation,
    ) {
        val header = state.toAccountViewHeaderState()
        val shimmerColor = LocalContentColor.current
            .combineAlpha(DisabledEmphasisAlpha)

        Avatar(
            modifier = Modifier
                .size(24.dp)
                .then(
                    if (header.isLoading) {
                        Modifier.shimmer()
                    } else {
                        Modifier
                    },
                ),
            color = if (header.isLoading) {
                shimmerColor
            } else {
                com.artemchep.keyguard.ui.defaultAvatarColor()
            },
        ) {
            val imageRes = header.logoImageRes
            if (imageRes != null) {
                Image(
                    modifier = Modifier
                        .fillMaxSize(),
                    painter = painterResource(imageRes),
                    contentDescription = null,
                )
            } else if (header.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(shimmerColor),
                )
            }
        }

        when (state.content) {
            AccountViewState.Content.Skeleton -> {
                Spacer(
                    modifier = Modifier
                        .width(6.dp),
                )
                Column(
                    modifier = Modifier
                        .heightIn(min = 24.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .height(10.dp)
                            .fillMaxWidth(0.22f)
                            .clip(MaterialTheme.shapes.medium)
                            .background(shimmerColor.copy(alpha = 0.35f))
                            .shimmer(),
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .height(14.dp)
                            .fillMaxWidth(0.34f)
                            .clip(MaterialTheme.shapes.medium)
                            .background(shimmerColor)
                            .shimmer(),
                    )
                }
            }

            is AccountViewState.Content.Data -> {
                Spacer(
                    modifier = Modifier
                        .width(6.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp),
                ) {
                    Text(
                        text = header.host.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        maxLines = 2,
                    )
                    Row(
                        modifier = Modifier
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = header.name.orEmpty(),
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                        )
                        if (header.beta) {
                            Spacer(
                                modifier = Modifier
                                    .width(4.dp),
                            )
                            BetaBadge()
                        }
                    }
                }
            }

            AccountViewState.Content.NotFound -> {
                // Keep the header minimal when the account does not exist.
            }
        }
    }
}
