package com.artemchep.keyguard.feature.home.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.core.store.bitwarden.size
import com.artemchep.keyguard.feature.auth.AccountViewRoute
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountItem
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.navigationNextEntryOrNull
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.account_main_add_account_title
import com.artemchep.keyguard.res.empty_value
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.AvatarBadgeIcon
import com.artemchep.keyguard.ui.AvatarBuilder
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardPremium
import com.artemchep.keyguard.ui.icons.SyncIcon
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.selectedContainer
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingListItem(
    item: SettingsItem,
    backgroundColor: Color,
    onClick: () -> Unit,
) {
    Column {
        FlatItemLayoutExpressive(
            backgroundColor = backgroundColor,
            shapeState = item.shapeState,
            leading = {
                Avatar(
                    shape = item.iconShape?.toShape()
                        ?: MaterialTheme.shapes.large,
                ) {
                    when {
                        item.leading != null -> {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.Center),
                            ) {
                                item.leading.invoke(this)
                            }
                        }

                        item.icon != null -> {
                            Icon(
                                modifier = Modifier
                                    .align(Alignment.Center),
                                imageVector = item.icon,
                                contentDescription = null,
                            )
                        }

                        else -> {
                        }
                    }
                }
            },
            trailing = {
                if (item.trailing != null) {
                    item.trailing.invoke(this)
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                }
                ChevronIcon()
            },
            content = {
                FlatItemTextContent(
                    title = {
                        Text(
                            text = textResource(item.title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    text = {
                        Text(
                            text = textResource(item.text),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            },
            footer = item.footer,
            onClick = onClick,
        )
    }
}

@Composable
fun SettingListAccountsItem(
    item: SettingsAccountsItem,
) {
    Column {
        val data by item.state

        var hasItems = false
        data.items.forEachIndexed { index, rawAccountItem ->
            val accountItem = rawAccountItem as? AccountItem.Item
                ?: return@forEachIndexed
            hasItems = true

            val shapeConstraintsAnd = if (index != data.items.lastIndex) {
                ShapeState.CENTER.inv()
            } else {
                ShapeState.END.inv()
            }
            val shapeState = getShapeState(
                list = data.items,
                index = index,
                predicate = { el, _ -> el is AccountItem.Item },
            ) and shapeConstraintsAnd
            val backgroundColor = when {
                accountItem.isSelected -> MaterialTheme.colorScheme.primaryContainer
                accountItem.isOpened -> MaterialTheme.colorScheme.selectedContainer
                else -> run {
                    if (LocalHasDetailPane.current) {
                        val nextEntry = navigationNextEntryOrNull()
                        val nextRoute = nextEntry?.route as? AccountViewRoute

                        val selected = nextRoute?.accountId?.id == accountItem.id
                        if (selected) {
                            return@run MaterialTheme.colorScheme.selectedContainer
                        }
                    }

                    Color.Unspecified
                }
            }
            FlatItemLayoutExpressive(
                backgroundColor = backgroundColor,
                shapeState = shapeState,
                leading = {
                    val accent = rememberSecretAccentColor(
                        accentLight = accountItem.accentLight,
                        accentDark = accountItem.accentDark,
                    )
                    AvatarBuilder(
                        icon = accountItem.icon,
                        accent = accent,
                        active = true,
                        badge = {
                            if (accountItem.hidden) {
                                AvatarBadgeIcon(
                                    imageVector = Icons.Outlined.VisibilityOff,
                                )
                            }
                            if (accountItem.premium) {
                                AvatarBadgeIcon(
                                    imageVector = Icons.Outlined.KeyguardPremium,
                                )
                            }
                        },
                    )
                },
                trailing = {
                    if (accountItem.error) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            tint = MaterialTheme.colorScheme.error,
                            contentDescription = null,
                        )
                    }

                    AnimatedVisibility(
                        visible = accountItem.syncing,
                        enter = fadeIn() + scaleIn(),
                        exit = scaleOut() + fadeOut(),
                    ) {
                        KeyguardLoadingIndicator(
                            modifier = Modifier
                                .size(24.dp),
                            contained = true,
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    ExpandedIfNotEmptyForRow(
                        accountItem.isSelected.takeIf { accountItem.selecting },
                    ) { selected ->
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                        )
                    }

                    ExpandedIfNotEmptyForRow(
                        Unit.takeIf { !accountItem.selecting },
                    ) {
                        ChevronIcon()
                    }
                },
                content = {
                    FlatItemTextContent(
                        title = {
                            if (accountItem.title != null) {
                                Text(
                                    text = accountItem.title,
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
                        text = accountItem.text
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
                    )
                },
                onClick = accountItem.onClick,
                onLongClick = accountItem.onLongClick,
            )
        }

        // Add new account
        val shapeState = if (hasItems) {
            ShapeState.END
        } else {
            ShapeState.ALL
        }
        FlatItemLayoutExpressive(
            shapeState = shapeState,
            leading = {
                Avatar {
                    Icon(
                        modifier = Modifier
                            .align(Alignment.Center),
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                    )
                }
            },
            content = {
                FlatItemTextContent(
                    title = {
                        Text(
                            text = stringResource(Res.string.account_main_add_account_title),
                        )
                    },
                )
            },
            onClick = data.onAddNewAccount,
        )
    }
}
