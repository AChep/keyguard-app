package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun AddAccountView(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)?,
) {
    FlatItem(
        modifier = modifier,
        elevation = 1.dp,
        title = {
            Text(
                text = stringResource(Res.strings.account_main_add_account_title),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        trailing = {
            ChevronIcon()
        },
        leading = {
            Icon(Icons.Outlined.PersonAdd, contentDescription = null)
        },
        onClick = onClick,
    )
}
