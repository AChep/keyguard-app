package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.ui.icons.IconSmallBox
import com.artemchep.keyguard.ui.theme.Dimens

@Composable
fun VaultViewQuickBadgesItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.QuickBadges,
) {
    FlowRow(
        modifier = modifier
            .padding(bottom = 24.dp)
            .padding(
                start = Dimens.buttonHorizontalPadding,
                end = Dimens.buttonHorizontalPadding,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item.actions.forEach { i ->
            SmartBadge(
                modifier = Modifier,
                icon = {
                    IconSmallBox(
                        main = Icons.Outlined.Email,
                    )
                },
                title = textResource(i.title),
                text = textResource(i.text),
                onClick = null,
            )
        }
    }
}
