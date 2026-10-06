package com.artemchep.keyguard.feature.keyguard.unlock

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.fido2_unlock_title
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Fido2UnlockButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.outlinedButtonColors(),
        elevation = null,
        border = ButtonDefaults.outlinedButtonBorder(),
        onClick = onClick,
        contentPadding = PaddingValues(16.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Key,
            contentDescription = stringResource(Res.string.fido2_unlock_title),
        )
    }
}
