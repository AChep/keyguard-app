package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewState.Content.Cipher
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.button.FavouriteToggleButton
import com.artemchep.keyguard.ui.icons.OfflineIcon

@Composable
internal fun RowScope.VaultViewCipherTitleActions(
    state: Cipher,
) {
    val elevated = state.locked.collectAsState()
    AnimatedVisibility(
        modifier = Modifier
            .alpha(LocalContentColor.current.alpha),
        visible = !elevated.value,
    ) {
        Icon(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .alpha(DisabledEmphasisAlpha),
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
        )
    }
    val synced = state.synced
    AnimatedVisibility(
        modifier = Modifier
            .alpha(LocalContentColor.current.alpha),
        visible = !synced,
    ) {
        OfflineIcon(
            modifier = Modifier
                .minimumInteractiveComponentSize(),
        )
    }
    FavouriteToggleButton(
        favorite = state.data.favorite,
        onChange = state.onFavourite,
    )
    IconButton(
        onClick = {
            state.onEdit?.invoke()
        },
        enabled = state.onEdit != null,
    ) {
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = null,
        )
    }
    OptionsButton(
        actions = state.actions,
    )
}
