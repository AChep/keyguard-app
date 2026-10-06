package com.artemchep.keyguard.feature.auth.bitwarden

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource

/** Looks the server up when tapped; nothing is sent before that. */
@Composable
internal fun LoginServerDiscoveryButton(
    modifier: Modifier = Modifier,
    discovery: LoginServerDiscovery,
) {
    val updatedOnClick by rememberUpdatedState(discovery.onClick)
    TextButton(
        modifier = modifier,
        enabled = discovery.onClick != null,
        onClick = {
            updatedOnClick?.invoke()
        },
    ) {
        if (discovery.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(ButtonDefaults.IconSize),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.TravelExplore,
                contentDescription = null,
            )
        }
        Spacer(
            modifier = Modifier
                .width(Dimens.buttonIconPadding),
        )
        Text(
            text = stringResource(Res.string.addaccount_server_discovery_button),
        )
    }
}
