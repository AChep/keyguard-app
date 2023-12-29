package com.artemchep.keyguard.feature.search.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.theme.Dimens
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun DropdownHeader(
    title: String,
    onClear: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Dimens.horizontalPadding),
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )

        val updatedOnClear by rememberUpdatedState(onClear)
        ExpandedIfNotEmptyForRow(
            valueOrNull = onClear,
        ) {
            TextButton(
                onClick = {
                    updatedOnClear?.invoke()
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Clear,
                    contentDescription = null,
                )
                Spacer(
                    modifier = Modifier
                        .width(Dimens.buttonIconPadding),
                )
                Text(
                    text = stringResource(Res.strings.reset),
                )
            }
        }
        Spacer(
            modifier = Modifier
                .width(8.dp),
        )
    }
}
