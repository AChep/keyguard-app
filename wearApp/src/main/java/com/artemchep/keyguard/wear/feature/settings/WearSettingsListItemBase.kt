package com.artemchep.keyguard.wear.feature.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountItem
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.account_main_add_account_title
import com.artemchep.keyguard.res.empty_value
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.AvatarBadgeIcon
import com.artemchep.keyguard.ui.AvatarBuilder
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.KeyguardPremium
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.wear.feature.picker.WearPickerRoute
import com.artemchep.keyguard.wear.ui.WearListAction
import com.artemchep.keyguard.wear.ui.WearSectionHeader
import com.artemchep.keyguard.wear.ui.WearSectionHeaderEmptyBehavior
import org.jetbrains.compose.resources.stringResource

sealed interface WearSettingsListItemBase {
    val id: String
}

data class WearSettingsListItem(
    override val id: String,
    val title: TextHolder,
    val text: TextHolder,
    val icon: ImageVector? = null,
    val route: Route,
) : WearSettingsListItemBase

data class WearSettingsListSection(
    override val id: String,
    val title: TextHolder?,
) : WearSettingsListItemBase

data class WearSettingsListAccountItem(
    override val id: String,
    val item: AccountItem.Item,
) : WearSettingsListItemBase

data class WearSettingsListAccountAdd(
    override val id: String,
    val actions: List<ContextItem>,
) : WearSettingsListItemBase

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WearSettingsListItem(
    modifier: Modifier = Modifier,
    item: WearSettingsListItem,
    onClick: () -> Unit,
    transformation: SurfaceTransformation? = null,
) {
    FilledTonalButton(
        modifier = modifier,
        onClick = onClick,
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
                    modifier = Modifier
                        .size(ButtonDefaults.IconSize),
                )
            }
        } else {
            null
        },
        transformation = transformation,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WearSettingsListSection(
    modifier: Modifier = Modifier,
    item: WearSettingsListSection,
    transformation: SurfaceTransformation? = null,
) {
    WearSectionHeader(
        title = textResource(item.title),
        modifier = modifier,
        emptyBehavior = WearSectionHeaderEmptyBehavior.Spacer4,
        transformation = transformation,
    )
}

@Composable
fun WearSettingsListAccountItem(
    modifier: Modifier = Modifier,
    item: WearSettingsListAccountItem,
    transformation: SurfaceTransformation? = null,
) {
    val account = item.item
    WearListAction(
        modifier = modifier,
        icon = {
            val accent = rememberSecretAccentColor(
                accentLight = account.accentLight,
                accentDark = account.accentDark,
            )
            AvatarBuilder(
                icon = account.icon,
                accent = accent,
                active = true,
                badge = {
                    if (account.hidden) {
                        AvatarBadgeIcon(
                            imageVector = Icons.Outlined.VisibilityOff,
                        )
                    }
                    if (account.premium) {
                        AvatarBadgeIcon(
                            imageVector = Icons.Outlined.KeyguardPremium,
                        )
                    }
                },
            )
        },
        title = {
            val title = account.title
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = stringResource(Res.string.empty_value),
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalContentColor.current
                        .combineAlpha(DisabledEmphasisAlpha),
                )
            }
        },
        text = account.text
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                // composable
                {
                    Text(
                        text = it,
                        maxLines = 1,
                    )
                }
            },
        onClick = account.onClick,
        transformation = transformation,
    )
}

@Composable
fun WearSettingsListAccountAdd(
    modifier: Modifier = Modifier,
    item: WearSettingsListAccountAdd,
    transformation: SurfaceTransformation? = null,
) {
    val titleText = stringResource(Res.string.account_main_add_account_title)

    val updatedItem by rememberUpdatedState(item)
    val updatedNavigationController by rememberUpdatedState(LocalNavigationController.current)
    OutlinedButton(
        modifier = modifier,
        icon = {
            Avatar {
                Icon(
                    modifier = Modifier
                        .align(Alignment.Center),
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                )
            }
        },
        label = {
            Text(
                text = stringResource(Res.string.account_main_add_account_title),
            )
        },
        onClick = {
            val actions = updatedItem.actions
            when (actions.size) {
                0 -> {
                    // Do nothing.
                }

                1 -> {
                    // Open the action directly.
                    val item = actions.firstOrNull() as? FlatItemAction?
                    item?.onClick?.invoke()
                }

                else -> {
                    val route = WearPickerRoute(
                        title = titleText,
                        actions = item.actions,
                    )
                    val intent = NavigationIntent.NavigateToRoute(route = route)
                    updatedNavigationController.queue(intent)
                }
            }
        },
        transformation = transformation,
    )
}
