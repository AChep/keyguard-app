package com.artemchep.keyguard.feature.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.search.component.DropdownHeader
import com.artemchep.keyguard.feature.search.filter.FilterItems
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AutofillButton
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.SwitchExpressive
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardAttachment
import com.artemchep.keyguard.ui.icons.KeyguardCipher
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.onWarningContainer
import com.artemchep.keyguard.ui.theme.verticalPaddingHalf
import com.artemchep.keyguard.ui.theme.warningContainer
import com.artemchep.keyguard.ui.wizard.WizardChoice
import com.artemchep.keyguard.ui.wizard.WizardSummaryRow
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ExportFormatStep(state: ExportState) {
    val format by state.formatFlow.collectAsState()
    Column(
        modifier = Modifier
            .selectableGroup(),
    ) {
        format.options.forEachIndexed { index, option ->
            val shapeState = getShapeState(
                list = format.options,
                index = index,
                predicate = { _, _ -> true },
            )
            WizardChoice(
                title = textResource(option.title),
                icon = option.icon ?: Icons.Outlined.Description,
                selected = option.selected,
                shapeState = shapeState,
                onClick = {
                    option.onClick?.invoke()
                },
            )
        }
    }
    ExportNote(
        text = format.note,
    )
}

@Composable
internal fun ExportItemsStep(state: ExportState) {
    val items by state.itemsFlow.collectAsState()
    val attachments by state.attachmentsFlow.collectAsState()
    val format by state.formatFlow.collectAsState()
    val filter by state.filterFlow.collectAsState()
    ExportItemsRows(
        items = items,
        attachments = attachments,
    )
    ExpandedIfNotEmpty(
        valueOrNull = Unit.takeIf { attachments.enabled },
    ) {
        ExportNote(
            text = format.attachmentsNote,
        )
    }
    Column {
        DropdownHeader(
            title = stringResource(Res.string.filter_header_title),
            actions = {
                ExportFilterActions(
                    filter = filter,
                )
            },
        )
        FilterItems(
            items = filter.items,
        )
    }
}

@Composable
private fun ExportItemsRows(
    items: ExportState.Items,
    attachments: ExportState.Attachments,
) {
    // The attachments of the items can be
    // exported only if there are some.
    val canExportAttachments = attachments.onToggle != null
    Column {
        FlatItemLayoutExpressive(
            shapeState = if (canExportAttachments) ShapeState.START else ShapeState.ALL,
            leading = {
                Icon(Icons.Outlined.KeyguardCipher, null)
            },
            content = {
                FlatItemTextContent(
                    title = {
                        Text(
                            text = stringResource(Res.string.items),
                        )
                    },
                    text = {
                        Text(
                            text = itemsCountText(items.count),
                        )
                    },
                )
            },
            trailing = {
                ChevronIcon()
            },
            onClick = items.onView,
        )
        if (canExportAttachments) {
            FlatItemLayoutExpressive(
                shapeState = ShapeState.END,
                leading = {
                    Icon(Icons.Outlined.KeyguardAttachment, null)
                },
                content = {
                    FlatItemTextContent(
                        title = {
                            Text(
                                text = stringResource(Res.string.exportaccount_include_attachments_title),
                            )
                        },
                        text = {
                            val size = attachments.size
                                ?: return@FlatItemTextContent
                            Text(size)
                        },
                    )
                },
                trailing = {
                    SwitchExpressive(
                        checked = attachments.enabled,
                        onCheckedChange = null,
                    )
                },
                onClick = attachments.onToggle,
            )
        }
    }
}

@Composable
private fun RowScope.ExportFilterActions(
    filter: ExportState.Filter,
) {
    val updatedOnSave by rememberUpdatedState(filter.onSave)
    if (updatedOnSave != null) {
        IconButton(
            onClick = {
                updatedOnSave?.invoke()
            },
        ) {
            Icon(
                imageVector = Icons.Outlined.Save,
                contentDescription = stringResource(Res.string.save),
            )
        }
    }
    val updatedOnClear by rememberUpdatedState(filter.onClear)
    if (updatedOnClear != null) {
        IconButton(
            onClick = {
                updatedOnClear?.invoke()
            },
        ) {
            Icon(
                imageVector = Icons.Outlined.Clear,
                contentDescription = stringResource(Res.string.reset),
            )
        }
    }
}

@Composable
internal fun ExportPasswordStep(state: ExportState) {
    val focusManager = LocalFocusManager.current
    val format by state.formatFlow.collectAsState()
    val password by state.passwordFlow.collectAsState()
    val confirmation by state.confirmationPasswordFlow.collectAsState()
    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.verticalPaddingHalf),
    ) {
        PasswordFlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.fieldHorizontalPadding),
            value = password.model,
            label = format.passwordLabel,
            shapeState = ShapeState.START,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
            trailing = {
                AutofillButton(
                    key = "password",
                    password = true,
                    onValueChange = { value ->
                        // A generated password can not be typed
                        // in again, so it fills both fields.
                        password.model.onSetText?.invoke(value)
                        confirmation.model.onSetText?.invoke(value)
                    },
                )
            },
        )
        PasswordFlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.fieldHorizontalPadding),
            value = confirmation.model,
            label = format.confirmPasswordLabel,
            shapeState = ShapeState.END,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        )
    }
}

@Composable
internal fun ExportReviewStep(state: ExportState) {
    val content by state.contentFlow.collectAsState()
    val format by state.formatFlow.collectAsState()
    val items by state.itemsFlow.collectAsState()
    val attachments by state.attachmentsFlow.collectAsState()
    ExportPermissionWarning(
        permission = content.writePermission,
    )
    Column(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WizardSummaryRow(
            icon = Icons.Outlined.Description,
            title = stringResource(Res.string.exportaccount_format_title),
            value = format.title,
        )
        WizardSummaryRow(
            icon = Icons.Outlined.KeyguardCipher,
            title = stringResource(Res.string.items),
            value = itemsCountText(items.count),
        )
        if (attachments.onToggle != null) {
            val size = attachments.size
            val value = when {
                !attachments.enabled -> stringResource(Res.string.disabled)
                size != null -> stringResource(Res.string.exportaccount_review_attachments_enabled, size)
                else -> stringResource(Res.string.enabled)
            }
            WizardSummaryRow(
                icon = Icons.Outlined.KeyguardAttachment,
                title = stringResource(Res.string.exportaccount_include_attachments_title),
                value = value,
            )
        }
    }
}

/**
 * Android before 10 needs a permission to
 * save the file to the downloads.
 */
@Composable
private fun ExportPermissionWarning(
    permission: PermissionState,
) {
    ExpandedIfNotEmpty(
        valueOrNull = permission as? PermissionState.Declined,
    ) { declined ->
        val updatedContext by rememberUpdatedState(LocalLeContext)
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onWarningContainer,
        ) {
            FlatItemSimpleExpressive(
                backgroundColor = MaterialTheme.colorScheme.warningContainer,
                leading = icon<RowScope>(Icons.Outlined.Storage, Icons.Outlined.Warning),
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_permission_write_external_storage_grant),
                    )
                },
                onClick = {
                    declined.ask(updatedContext)
                },
            )
        }
    }
}

@Composable
private fun ExportNote(
    text: String,
) {
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun itemsCountText(count: Int): String = pluralStringResource(
    Res.plurals.exportaccount_items_count_plural,
    count,
    count,
)
