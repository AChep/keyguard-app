package com.artemchep.keyguard.feature.imports

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
import androidx.compose.material.icons.outlined.Code
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
import com.artemchep.keyguard.feature.transfer.TransferItemRow
import com.artemchep.keyguard.feature.transfer.transferSkippedNotes
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.icons.KeyguardGpgKey
import com.artemchep.keyguard.ui.icons.KeyguardNote
import com.artemchep.keyguard.ui.icons.KeyguardPasskey
import com.artemchep.keyguard.ui.icons.KeyguardSshKey
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private fun ImportCountLine.Kind.icon(): ImageVector = when (this) {
    ImportCountLine.Kind.Login -> Icons.Outlined.Password
    ImportCountLine.Kind.Passkey -> Icons.Outlined.KeyguardPasskey
    ImportCountLine.Kind.Otp -> Icons.Outlined.KeyguardTwoFa
    ImportCountLine.Kind.Card -> Icons.Outlined.CreditCard
    ImportCountLine.Kind.Identity -> Icons.Outlined.Person
    ImportCountLine.Kind.Note -> Icons.Outlined.KeyguardNote
    ImportCountLine.Kind.SshKey -> Icons.Outlined.KeyguardSshKey
    ImportCountLine.Kind.GpgKey -> Icons.Outlined.KeyguardGpgKey
    ImportCountLine.Kind.Environment -> Icons.Outlined.Code
    ImportCountLine.Kind.Folder -> Icons.Outlined.Folder
}

internal fun LazyListScope.importReviewContent(
    stage: ImportState.Stage.Review,
) {
    item {
        Column {
            stage.countLines().forEach { line ->
                ImportCountRow(
                    line = line,
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
            TransferItemRow(
                item = item.item,
                selected = item.selected,
                onSelectedChange = item.onSelectedChange,
            )
        }
    }
    transferSkippedNotes(
        notes = stage.skipped,
    )
    // Debug builds only, save the debug payload into
    // a file.
    item {
        Column {
            ImportTextButton(
                text = "Save payload to Downloads",
                onClick = stage.onSaveDebugPayload,
            )
        }
    }
}

internal fun LazyListScope.importEmptyContent(
    stage: ImportState.Stage.Empty,
) {
    item {
        Text(
            modifier = Modifier
                .padding(
                    horizontal = Dimens.textHorizontalPadding,
                    vertical = 16.dp,
                ),
            text = stage.message,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    transferSkippedNotes(
        notes = stage.skipped,
    )
    item {
        Column {
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
            ImportTextButton(
                text = stringResource(Res.string.close),
                onClick = stage.onClose,
            )
        }
    }
}

@Composable
internal fun ColumnScope.ImportPasswordContent(
    stage: ImportState.Stage.Password,
) {
    Text(
        modifier = Modifier
            .padding(
                horizontal = Dimens.textHorizontalPadding,
                vertical = 8.dp,
            ),
        text = stage.text,
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(
        modifier = Modifier
            .height(8.dp),
    )
    PasswordFlatTextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.fieldHorizontalPadding),
        label = stringResource(Res.string.password),
        value = stage.password,
    )
    Spacer(
        modifier = Modifier
            .height(8.dp),
    )
    ImportTextButton(
        text = stringResource(Res.string.cancel),
        onClick = stage.onCancel,
    )
}

@Composable
internal fun ColumnScope.ImportDoneContent(
    stage: ImportState.Stage.Done,
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
            text = stringResource(Res.string.import_success_title),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = pluralStringResource(
            Res.plurals.import_success_text,
            stage.itemCount,
            stage.itemCount,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = LocalContentColor.current
            .combineAlpha(alpha = MediumEmphasisAlpha),
    )
    stage.countLines().forEach { line ->
        ImportCountRow(
            line = line,
        )
    }
    Spacer(
        modifier = Modifier
            .height(16.dp),
    )
    ImportTextButton(
        text = stringResource(Res.string.close),
        onClick = stage.onClose,
    )
}

@Composable
internal fun ColumnScope.ImportErrorContent(
    stage: ImportState.Stage.Error,
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
private fun ColumnScope.ImportCountRow(
    line: ImportCountLine,
) {
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
            imageVector = line.kind.icon(),
            contentDescription = null,
            tint = LocalContentColor.current
                .combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Text(
            text = pluralStringResource(line.res, line.count, line.count),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun ColumnScope.ImportTextButton(
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
