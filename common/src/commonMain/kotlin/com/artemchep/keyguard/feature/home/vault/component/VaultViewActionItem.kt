package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent

@Composable
fun VaultViewActionItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Action,
) {
    FlatItemLayoutExpressive(
        modifier = modifier,
        elevation = item.elevation,
        shapeState = item.shapeState,
        leading = item.leading,
        content = {
            FlatItemTextContent(
                title = {
                    Text(item.title)
                },
                text = if (item.text != null) {
                    {
                        Text(item.text)
                    }
                } else {
                    null
                },
            )

            if (item.badge != null) {
                Spacer(
                    modifier = Modifier
                        .height(4.dp),
                )
                com.artemchep.keyguard.ui.Ah(
                    score = item.badge.score,
                    text = item.badge.text,
                )
            }
        },
        trailing = item.trailing,
        onClick = item.onClick,
    )
}
