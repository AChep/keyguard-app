package com.artemchep.keyguard.feature.credentialexchange.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import com.artemchep.keyguard.feature.credentialexchange.CredentialExchangeItemRow
import com.artemchep.keyguard.feature.credentialexchange.credentialExchangeSkippedNotes
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.KeyguardNote
import com.artemchep.keyguard.ui.icons.KeyguardPasskey
import com.artemchep.keyguard.ui.icons.KeyguardSshKey
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private data class ImportCountRow(
    val icon: ImageVector,
    val count: Int,
    val res: PluralStringResource,
)

private fun reviewCountRows(
    stage: CredentialExchangeImportState.Stage.Review,
): List<ImportCountRow> = listOf(
    ImportCountRow(
        icon = Icons.Outlined.Password,
        count = stage.counts.loginCount,
        res = Res.plurals.credential_exchange_import_review_logins_note,
    ),
    ImportCountRow(
        icon = Icons.Outlined.KeyguardPasskey,
        count = stage.counts.passkeyCount,
        res = Res.plurals.credential_exchange_import_review_passkeys_note,
    ),
    ImportCountRow(
        icon = Icons.Outlined.KeyguardTwoFa,
        count = stage.counts.otpCount,
        res = Res.plurals.credential_exchange_import_review_otp_note,
    ),
    ImportCountRow(
        icon = Icons.Outlined.CreditCard,
        count = stage.counts.cardCount,
        res = Res.plurals.credential_exchange_import_review_cards_note,
    ),
    ImportCountRow(
        icon = Icons.Outlined.Person,
        count = stage.counts.identityCount,
        res = Res.plurals.credential_exchange_import_review_identities_note,
    ),
    ImportCountRow(
        icon = Icons.Outlined.KeyguardNote,
        count = stage.counts.noteCount,
        res = Res.plurals.credential_exchange_import_review_notes_note,
    ),
    ImportCountRow(
        icon = Icons.Outlined.KeyguardSshKey,
        count = stage.counts.sshKeyCount,
        res = Res.plurals.credential_exchange_import_review_ssh_keys_note,
    ),
    ImportCountRow(
        icon = Icons.Outlined.Folder,
        count = stage.folderCount,
        res = Res.plurals.credential_exchange_import_review_folders_note,
    ),
)

internal fun LazyListScope.credentialExchangeImportReviewContent(
    stage: CredentialExchangeImportState.Stage.Review,
) {
    item {
        Column {
            reviewCountRows(stage).forEach { row ->
                CredentialExchangeImportCountRow(
                    row = row,
                )
            }
        }
    }
    if (stage.items.isNotEmpty()) {
        item {
            Section(
                expressive = true,
            )
        }
        items(stage.items) { item ->
            CredentialExchangeItemRow(
                item = item.item,
                selected = item.selected,
                onSelectedChange = item.onSelectedChange,
            )
        }
    }
    credentialExchangeSkippedNotes(
        notes = stage.skipped,
    )
    // Debug builds only, save the debug payload into
    // a file.
    item {
        Column {
            CredentialExchangeImportTextButton(
                text = "Save payload to Downloads",
                onClick = stage.onSaveDebugPayload,
            )
        }
    }
}

internal fun LazyListScope.credentialExchangeImportEmptyContent(
    stage: CredentialExchangeImportState.Stage.Empty,
) {
    item {
        Text(
            modifier = Modifier
                .padding(
                    horizontal = Dimens.textHorizontalPadding,
                    vertical = 16.dp,
                ),
            text = stringResource(Res.string.credential_exchange_import_empty_label),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    credentialExchangeSkippedNotes(
        notes = stage.skipped,
    )
    item {
        Column {
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
            CredentialExchangeImportTextButton(
                text = stringResource(Res.string.close),
                onClick = stage.onClose,
            )
        }
    }
}

@Composable
internal fun ColumnScope.CredentialExchangeImportDoneContent(
    stage: CredentialExchangeImportState.Stage.Done,
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
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(Res.string.credential_exchange_import_success_title),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = pluralStringResource(
            Res.plurals.credential_exchange_import_success_text,
            stage.itemCount,
            stage.itemCount,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = LocalContentColor.current
            .combineAlpha(alpha = MediumEmphasisAlpha),
    )
    // Hides itself when no folder was created. It is the whole account of a
    // folders-only import, whose item sentence above legitimately reads zero.
    CredentialExchangeImportCountRow(
        row = ImportCountRow(
            icon = Icons.Outlined.Folder,
            count = stage.folderCount,
            res = Res.plurals.credential_exchange_import_review_folders_note,
        ),
    )
    Spacer(
        modifier = Modifier
            .height(16.dp),
    )
    CredentialExchangeImportTextButton(
        text = stringResource(Res.string.close),
        onClick = stage.onClose,
    )
}

@Composable
internal fun ColumnScope.CredentialExchangeImportErrorContent(
    stage: CredentialExchangeImportState.Stage.Error,
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

@Composable
private fun ColumnScope.CredentialExchangeImportCountRow(
    row: ImportCountRow,
) {
    if (row.count <= 0) {
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.textHorizontalPadding,
                vertical = 4.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = row.icon,
            contentDescription = null,
            tint = LocalContentColor.current
                .combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Text(
            text = pluralStringResource(row.res, row.count, row.count),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ColumnScope.CredentialExchangeImportTextButton(
    text: String,
    onClick: (() -> Unit)?,
) {
    if (onClick == null) {
        return
    }
    val updatedOnClick by rememberUpdatedState(onClick)
    TextButton(
        modifier = Modifier
            .padding(horizontal = Dimens.buttonHorizontalPadding),
        onClick = {
            updatedOnClick.invoke()
        },
    ) {
        Text(
            text = text,
        )
    }
}
