package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.theme.Dimens

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
                horizontal = Dimens.buttonHorizontalPadding,
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
                    .width(ButtonDefaults.IconSpacing),
            )
        }
        Text(text)
    }
}
