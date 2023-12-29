package com.artemchep.keyguard.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.theme.Dimens

@Composable
fun ErrorView(
    icon: (@Composable () -> Unit)? = {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
        )
    },
    text: @Composable () -> Unit = {
        Text(text = "Something went wrong")
    },
    exception: Throwable? = null,
) {
    Column(
        modifier = Modifier
            .padding(
                vertical = 16.dp,
                horizontal = Dimens.horizontalPadding,
            )
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.error,
                LocalTextStyle provides MaterialTheme.typography.labelLarge,
            ) {
                if (icon != null) {
                    icon()
                    Spacer(
                        modifier = Modifier
                            .width(16.dp),
                    )
                }
                text()
            }
        }
        ExpandedIfNotEmpty(
            valueOrNull = exception,
        ) { e ->
            ElevatedButton(
                modifier = Modifier
                    .padding(top = 8.dp),
                onClick = {
                },
            ) {
                Text(text = "View details")
            }
        }
    }
}
