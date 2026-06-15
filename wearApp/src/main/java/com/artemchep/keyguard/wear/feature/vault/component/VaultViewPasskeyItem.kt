package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.wear.ui.WearListAction
import com.artemchep.keyguard.wear.util.joinToBulletStringOrNull

@Composable
fun WearVaultViewPasskeyItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Passkey,
    transformation: SurfaceTransformation? = null,
) {
    WearListAction(
        modifier = modifier
            .fillMaxWidth(),
        title = item.value
            ?: item.source.rpName
            ?: item.source.userDisplayName
            ?: "Passkey",
        text = joinToBulletStringOrNull(
            item.source.rpId,
            item.source.userName,
        ),
        onClick = item.onClick,
        leading = {
            Icon(
                imageVector = Icons.Outlined.Key,
                contentDescription = null,
            )
        },
        transformation = transformation,
    )
}
