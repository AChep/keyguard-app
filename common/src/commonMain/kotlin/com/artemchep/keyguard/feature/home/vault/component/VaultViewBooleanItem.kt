package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.FlatItemTextContent

@Composable
fun VaultViewSwitchItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Switch,
) {
    FlatDropdownSimpleExpressive(
        modifier = modifier,
        shapeState = item.shapeState,
        content = {
            FlatItemTextContent(
                text = {
                    Text(
                        text = item.title,
                    )
                },
            )
        },
        trailing = {
            // TODO
//            CompositionLocalProvider(
//                LocalMinimumInteractiveComponentEnforcement provides false,
//            ) {
            Switch(
                checked = item.value,
                onCheckedChange = null,
            )
//            }
        },
        dropdown = item.dropdown,
    )
}
