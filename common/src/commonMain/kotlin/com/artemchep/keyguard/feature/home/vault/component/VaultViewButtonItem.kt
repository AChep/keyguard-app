package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem

@Composable
fun VaultViewButtonItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Button,
) {
    VaultViewButtonItem(
        modifier = modifier,
        leading = item.leading,
        text = item.text,
        onClick = item.onClick,
    )
}

@Composable
fun VaultViewButtonItem(
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    text: String,
    onClick: (() -> Unit)? = null,
) {
    val updatedOnClick by rememberUpdatedState(onClick)
    Button(
        modifier = modifier
            .padding(
                horizontal = 8.dp,
                vertical = 4.dp,
            ),
        enabled = updatedOnClick != null,
        onClick = {
            updatedOnClick?.invoke()
        },
    ) {
        if (leading != null) {
            leading.invoke(this)
            Spacer(
                modifier = Modifier
                    .width(16.dp),
            )
        }
        Text(text)
    }
}
