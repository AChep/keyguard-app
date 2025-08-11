package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha

@Composable
fun VaultViewIdentityItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Identity,
) {
    Column(
        modifier = modifier,
    ) {
        val title = item.data.title
        if (!title.isNullOrBlank()) {
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.horizontalPadding),
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
            )
        }
        val name = listOfNotNull(
            item.data.firstName,
            item.data.middleName,
            item.data.lastName,
        ).joinToString(separator = " ")
        if (name.isNotBlank()) {
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.horizontalPadding),
                text = name,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (item.actions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.contentPadding),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                item.actions.forEachIndexed { index, action ->
                    val isFirst = index == 0
                    val isLast = index == item.actions.lastIndex

                    val shape = MaterialTheme.shapes.large
                        .let { shape ->
                            shape.copy(
                                topStart = shape.topStart.takeIf { isFirst }
                                    ?: expressiveInnerCornerSize,
                                topEnd = shape.topEnd.takeIf { isLast }
                                    ?: expressiveInnerCornerSize,
                                bottomStart = shape.bottomStart.takeIf { isFirst }
                                    ?: expressiveInnerCornerSize,
                                bottomEnd = shape.bottomEnd.takeIf { isLast }
                                    ?: expressiveInnerCornerSize,
                            )
                        }
                    Ah(
                        modifier = Modifier
                            .weight(1f),
                        shape = shape,
                        icon = {
                            if (action.leading != null) {
                                action.leading.invoke()
                            } else if (action.icon != null) {
                                Icon(action.icon, null)
                            }
                        },
                        title = textResource(action.title),
                        onClick = action.onClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun Ah(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    title: String,
    onClick: (() -> Unit)?,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.primary,
        ) {
            Column(
                modifier = Modifier
                    .then(
                        if (onClick != null) {
                            Modifier
                                .clickable {
                                    onClick()
                                }
                        } else {
                            Modifier
                        },
                    )
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                icon()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
