package com.artemchep.keyguard.feature.imports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The snapshots of an open Keyguard backup, one of them selected.
 */
internal fun LazyListScope.importSnapshotsContent(
    stage: ImportState.Stage.Snapshots,
) {
    item("snapshots.header") {
        Text(
            modifier = Modifier
                .padding(
                    horizontal = Dimens.textHorizontalPadding,
                    vertical = 8.dp,
                ),
            text = stringResource(Res.string.import_backup_snapshots_text),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    itemsIndexed(
        items = stage.items,
        key = { _, snapshot -> "snapshots.${snapshot.key}" },
    ) { index, snapshot ->
        val shapeState = getShapeState(
            list = stage.items,
            index = index,
            predicate = { _, _ -> true },
        )
        ImportSnapshotItem(
            snapshot = snapshot,
            shapeState = shapeState,
        )
    }
    item("snapshots.cancel") {
        Column {
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
            ImportTextButton(
                text = stringResource(Res.string.cancel),
                onClick = stage.onCancel,
            )
        }
    }
}

@Composable
private fun ImportSnapshotItem(
    snapshot: ImportState.Stage.Snapshots.Snapshot,
    shapeState: Int,
) {
    val backgroundColor = if (snapshot.selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Unspecified
    }
    val itemsText = pluralStringResource(
        Res.plurals.import_backup_snapshot_items,
        snapshot.itemCount,
        snapshot.itemCount,
    )
    val text = if (snapshot.attachmentCount > 0) {
        val attachmentsText = pluralStringResource(
            Res.plurals.import_backup_snapshot_attachments,
            snapshot.attachmentCount,
            snapshot.attachmentCount,
        )
        "$itemsText · $attachmentsText"
    } else {
        itemsText
    }
    FlatItemSimpleExpressive(
        modifier = Modifier
            .semantics {
                selected = snapshot.selected
                role = Role.RadioButton
            },
        shapeState = shapeState,
        backgroundColor = backgroundColor,
        title = {
            Text(
                text = snapshot.createdAt,
            )
        },
        text = {
            Text(
                text = text,
            )
        },
        trailing = {
            RadioButton(
                selected = snapshot.selected,
                onClick = null,
            )
        },
        onClick = snapshot.onClick,
    )
}
