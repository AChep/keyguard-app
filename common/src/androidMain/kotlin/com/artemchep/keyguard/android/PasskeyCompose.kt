package com.artemchep.keyguard.android

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.OtherScaffold
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource

@Composable
fun PasskeyError(
    modifier: Modifier = Modifier,
    title: String?,
    message: String,
    onFinish: () -> Unit,
) {
    OtherScaffold(
        modifier = modifier,
    ) {
        Icon(
            modifier = Modifier
                .align(Alignment.Start),
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        if (title != null) {
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
            Text(
                modifier = Modifier
                    .fillMaxWidth(),
                text = title,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(
            modifier = Modifier
                .height(24.dp),
        )
        Text(
            modifier = Modifier
                .fillMaxWidth(),
            text = message,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        val updatedOnFinish by rememberUpdatedState(onFinish)
        Button(
            modifier = Modifier
                .fillMaxWidth(),
            onClick = {
                updatedOnFinish()
            },
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = null,
            )
            Spacer(
                modifier = Modifier
                    .width(Dimens.buttonIconPadding),
            )
            Text(
                text = stringResource(Res.string.close),
            )
        }
    }
}
