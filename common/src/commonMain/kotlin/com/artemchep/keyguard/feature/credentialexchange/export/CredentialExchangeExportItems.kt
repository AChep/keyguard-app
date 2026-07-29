package com.artemchep.keyguard.feature.credentialexchange.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.Dimens

/**
 * The error stage's whole content: an icon and a sentence, with no retry.
 */
@Composable
internal fun ColumnScope.CredentialExchangeExportErrorContent(
    stage: CredentialExchangeExportState.Stage.Error,
) {
    Row(
        modifier = Modifier
            .padding(
                horizontal = Dimens.textHorizontalPadding,
                vertical = 16.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stage.message,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * The unavailable stage's whole content.
 *
 * A neutral icon rather than the error tint, because nothing went wrong — the
 * account the picker offered simply is not there any more. Closing reports a
 * cancellation, so the requesting app is told the user withdrew rather than that
 * Keyguard failed.
 */
@Composable
internal fun ColumnScope.CredentialExchangeExportUnavailableContent(
    stage: CredentialExchangeExportState.Stage.Unavailable,
) {
    Row(
        modifier = Modifier
            .padding(
                horizontal = Dimens.textHorizontalPadding,
                vertical = 16.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
        )
        Text(
            text = stage.message,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    CredentialExchangeExportCancelButton(
        onDeny = stage.onClose,
    )
}

/**
 * The mapping stage's whole content: the same skeleton rows the screen shows before
 * its first state arrives, so passing the gate reads as continued progress, plus the
 * cancel that answers the requesting app if the wait is unacceptable.
 *
 * No copy of its own on purpose — what must stop being on screen here is the
 * verification form, and any sentence of its own would only compete with the skeleton
 * for saying "still working".
 */
@Composable
internal fun ColumnScope.CredentialExchangeExportMappingContent(
    stage: CredentialExchangeExportState.Stage.Mapping,
) {
    repeat(SKELETON_ITEM_COUNT) {
        SkeletonItem()
    }
    Spacer(
        modifier = Modifier
            .height(16.dp),
    )
    CredentialExchangeExportCancelButton(
        onDeny = stage.onDeny,
    )
}
