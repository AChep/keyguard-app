package com.artemchep.keyguard.android

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OtherScaffold
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
fun CredentialScaffold(
    onCancel: () -> Unit,
    titleText: String,
    subtitle: @Composable ColumnScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    ExtensionScaffold(
        header = {
            Row(
                modifier = Modifier
                    .padding(
                        start = Dimens.horizontalPadding,
                        end = 8.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.CenterVertically),
                ) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                    ProvideTextStyle(MaterialTheme.typography.titleSmall) {
                        subtitle()
                    }
                }

                TextButton(
                    onClick = onCancel,
                ) {
                    Icon(Icons.Outlined.Close, null)
                    Spacer(
                        modifier = Modifier
                            .width(Dimens.buttonIconPadding),
                    )
                    Text(
                        text = stringResource(Res.string.cancel),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
    ) {
        content()
    }
}

@Composable
fun CredentialSubtitlePublicKey(
    modifier: Modifier = Modifier,
    username: String,
    rpId: String,
) {
    Row(
        modifier = modifier,
    ) {
        Text(
            text = username,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Text(
            modifier = Modifier
                .weight(1f, fill = false),
            text = "@$rpId",
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}

@Composable
fun CredentialSubtitlePassword(
    modifier: Modifier = Modifier,
    username: String,
) {
    Row(
        modifier = modifier,
    ) {
        Text(
            text = username,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}

@Composable
fun CredentialError(
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
