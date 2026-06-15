package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonDefaults.ButtonHorizontalPadding
import androidx.wear.compose.material3.ChildButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.reused_password
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearVaultViewReusedPasswordItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.ReusedPassword,
    transformation: SurfaceTransformation? = null,
) {
    val contentColor = MaterialTheme.colorScheme.error
    val backgroundColor = MaterialTheme.colorScheme.errorContainer
        .combineAlpha(DisabledEmphasisAlpha)
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
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
            )
        },
        label = {
            Text(
                text = stringResource(Res.string.reused_password),
                style = MaterialTheme.typography.labelSmall,
            )
        },
        transformation = transformation,
    )
}
