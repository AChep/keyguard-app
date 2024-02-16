package com.artemchep.keyguard.feature.home.settings.accounts.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountItem
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.home.vault.component.rememberSecretAccentColor
import com.artemchep.keyguard.ui.AvatarBadgeIcon
import com.artemchep.keyguard.ui.AvatarBuilder
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardPremium
import com.artemchep.keyguard.ui.icons.SyncIcon
import com.artemchep.keyguard.ui.theme.selectedContainer

@Composable
fun AccountListItem(
    item: AccountItem,
) = when (item) {
    is AccountItem.Section -> AccountListItemSection(item)
    is AccountItem.Item -> AccountListItemText(item)
}

@Composable
fun AccountListItemSection(
    item: AccountItem.Section,
) {
    Section(
        text = item.text.orEmpty(),
    )
}

@Composable
fun AccountListItemText(
    item: AccountItem.Item,
) {
    @Composable
    fun TextContent(text: String) =
        Text(
            text = text,
            maxLines = 1,
        )

    val backgroundColor = when {
        item.isSelected -> MaterialTheme.colorScheme.primaryContainer
        item.isOpened -> MaterialTheme.colorScheme.selectedContainer
        else -> Color.Unspecified
    }
    FlatItemLayout(
        backgroundColor = backgroundColor,
        leading = {
            val accent = rememberSecretAccentColor(
                accentLight = item.accentLight,
                accentDark = item.accentDark,
            )
            AvatarBuilder(
                icon = item.icon,
                accent = accent,
                active = true,
                badge = {
                    if (item.hidden) {
                        AvatarBadgeIcon(
                            imageVector = Icons.Outlined.VisibilityOff,
                        )
                    }
                    if (item.premium) {
                        AvatarBadgeIcon(
                            imageVector = Icons.Outlined.KeyguardPremium,
                        )
                    }
                },
            )
        },
        actions = item.actions,
        content = {
            FlatItemTextContent(
                title = {
                    Text(
                        text = item.title,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                text = item.text
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        // composable
                        {
                            TextContent(text = it)
                        }
                    },
            )
        },
        trailing = {
            if (item.error) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                    contentDescription = null,
                )
            }

            AnimatedVisibility(
                visible = item.syncing,
                enter = fadeIn() + scaleIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                SyncIcon(
                    rotating = true,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            ExpandedIfNotEmptyForRow(
                item.isSelected.takeIf { item.selecting },
            ) { selected ->
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                )
            }

            ExpandedIfNotEmptyForRow(
                Unit.takeIf { !item.selecting },
            ) {
                ChevronIcon()
            }
        },
        onClick = item.onClick,
        onLongClick = item.onLongClick,
    )
}

@Composable
fun VaultListItemTextIcon(
    item: AccountItem.Item,
) = Box(
    modifier = Modifier
        .size(24.dp),
) {
    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            val color = rememberSecretAccentColor(
                accentLight = item.accentLight,
                accentDark = item.accentDark,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color, CircleShape),
            )
        }
    }
    Row(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .background(
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.shapes.extraSmall,
            ),
    ) {
        if (item.actionNeeded) {
            Icon(
                modifier = Modifier
                    .size(15.dp)
                    .padding(1.dp),
                imageVector = Icons.Outlined.Warning,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                contentDescription = null,
            )
        }
    }
}
