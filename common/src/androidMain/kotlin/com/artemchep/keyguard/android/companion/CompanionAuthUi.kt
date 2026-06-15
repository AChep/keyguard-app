package com.artemchep.keyguard.android.companion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
fun CompanionAuthHeader() {
    Row(
        modifier = Modifier
            .padding(
                vertical = 8.dp,
                horizontal = Dimens.horizontalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Watch,
            contentDescription = null,
        )
        Spacer(
            modifier = Modifier
                .width(16.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f),
        ) {
            Text(
                text = stringResource(Res.string.companionauth_header_title),
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
            Text(
                text = stringResource(Res.string.companionauth_header_text),
                style = MaterialTheme.typography.titleMedium,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
            )
        }
        Spacer(
            modifier = Modifier
                .width(8.dp),
        )
        val context by rememberUpdatedState(newValue = LocalContext.current)
        TextButton(
            onClick = {
                context.closestActivityOrNull?.finish()
            },
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
}
