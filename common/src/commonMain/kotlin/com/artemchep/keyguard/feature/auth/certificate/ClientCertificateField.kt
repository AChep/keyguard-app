package com.artemchep.keyguard.feature.auth.certificate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_mutual_tls_file_label
import com.artemchep.keyguard.res.select_file
import com.artemchep.keyguard.ui.FakeFlatTextField
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource

@Composable
fun ClientCertificateField(
    displayName: String?,
    issuerText: String?,
    expiresText: String?,
    onPick: (() -> Unit)?,
    onClear: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    FakeFlatTextField(
        modifier = modifier.padding(horizontal = Dimens.fieldHorizontalPadding),
        label = stringResource(Res.string.addaccount_mutual_tls_file_label),
        value = {
            Column {
                Text(displayName ?: stringResource(Res.string.select_file))
                if (issuerText != null) Text(
                    issuerText,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (expiresText != null) Text(
                    expiresText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        onClick = onPick,
        onClear = onClear,
        leading = { IconBox(main = Icons.Outlined.Security) },
    )
}
