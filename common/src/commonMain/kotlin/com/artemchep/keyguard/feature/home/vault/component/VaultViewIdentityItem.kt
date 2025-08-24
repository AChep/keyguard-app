package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import io.ktor.sse.SPACE

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VaultViewIdentityItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Identity,
) {
    Column(
        modifier = modifier
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val title = item.data.title
        if (!title.isNullOrBlank()) {
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.textHorizontalPadding),
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
                    .padding(horizontal = Dimens.textHorizontalPadding),
                text = name,
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        if (item.actions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.contentPadding),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item.actions.forEachIndexed { index, action ->
                    IdentityActionButton(
                        modifier = Modifier
                            .weight(1f),
                        action = action,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.IdentityActionButton(
    modifier: Modifier = Modifier,
    action: FlatItemAction,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val updatedOnClick by rememberUpdatedState(action.onClick)
        Button(
            onClick = {
                updatedOnClick?.invoke()
            },
            colors = ButtonDefaults.buttonColors(),
            shapes = ButtonDefaults.shapes(),
            enabled = updatedOnClick != null,
        ) {
            Box(
                modifier = Modifier
                    .padding(
                        horizontal = 6.dp,
                        vertical = 4.dp,
                    ),
            ) {
                if (action.leading != null) {
                    action.leading.invoke()
                } else if (action.icon != null) {
                    Icon(action.icon, null)
                }
            }
        }
        Spacer(
            modifier = Modifier
                .height(4.dp),
        )
        Text(
            text = textResource(action.title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
