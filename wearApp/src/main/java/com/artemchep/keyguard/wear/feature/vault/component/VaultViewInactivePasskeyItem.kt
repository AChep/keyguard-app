package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonDefaults.ButtonHorizontalPadding
import androidx.wear.compose.material3.ChildButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.passkey_available
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.info
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearVaultViewInactivePasskeyItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.InactivePasskey,
    transformation: SurfaceTransformation? = null,
) {
    val contentColor = androidx.compose.material3.MaterialTheme.colorScheme.info
        .combineAlpha(MediumEmphasisAlpha)
        .compositeOver(LocalContentColor.current)
    ChildButton(
        modifier = modifier
            .fillMaxWidth(),
        onClick = {},
        contentPadding = PaddingValues(
            horizontal = ButtonHorizontalPadding,
        ),
        colors = ButtonDefaults.childButtonColors().run {
            copy(
                disabledIconColor = contentColor,
                disabledContentColor = contentColor,
                disabledSecondaryContentColor = secondaryContentColor,
            )
        },
        enabled = false,
        icon = {
            Icon(
                modifier = Modifier
                    .size(16.dp),
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
            )
        },
        label = {
            Text(
                text = stringResource(Res.string.passkey_available),
                style = MaterialTheme.typography.labelSmall,
            )
        },
        transformation = transformation,
    )
}
