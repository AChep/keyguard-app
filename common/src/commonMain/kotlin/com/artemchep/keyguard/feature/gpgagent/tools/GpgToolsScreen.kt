package com.artemchep.keyguard.feature.gpgagent.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.LocalNavigationNodeVisualStack
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.twopane.TwoPaneNavigationContent
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.choose_file
import com.artemchep.keyguard.res.contactus_english_note
import com.artemchep.keyguard.res.contactus_thanks_note
import com.artemchep.keyguard.res.gpg_tools_add_recipients
import com.artemchep.keyguard.res.gpg_tools_armor_label
import com.artemchep.keyguard.res.gpg_tools_choose_signing_key
import com.artemchep.keyguard.res.gpg_tools_clear_file
import com.artemchep.keyguard.res.gpg_tools_decrypt_keys_note
import com.artemchep.keyguard.res.gpg_tools_encrypted_text_label
import com.artemchep.keyguard.res.gpg_tools_pasted_public_key_label
import com.artemchep.keyguard.res.gpg_tools_public_key_add
import com.artemchep.keyguard.res.gpg_tools_select_input_file
import com.artemchep.keyguard.res.gpg_tools_select_recipients
import com.artemchep.keyguard.res.gpg_tools_select_signature_file
import com.artemchep.keyguard.res.gpg_tools_select_signing_key
import com.artemchep.keyguard.res.gpg_tools_signature_label
import com.artemchep.keyguard.res.gpg_tools_sign_encrypted_message
import com.artemchep.keyguard.res.gpg_tools_sign_none
import com.artemchep.keyguard.res.gpg_tools_signed_text_label
import com.artemchep.keyguard.res.gpg_tools_stored_public_keys_note
import com.artemchep.keyguard.res.input
import com.artemchep.keyguard.res.remove
import com.artemchep.keyguard.res.run_action
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.KeyguardDropdownMenu
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.skeleton.SkeletonSection
import com.artemchep.keyguard.ui.tabs.SegmentedButtonGroup
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun GpgToolsScreen() {
    // GPG tools acts like Vault/Send/Settings: the root route hosts sub-windows.
    val visualStack = LocalNavigationNodeVisualStack.current
        .run {
            removeAt(lastIndex)
        }
    CompositionLocalProvider(
        LocalNavigationNodeVisualStack provides visualStack,
    ) {
        NavigationRouter(
            id = GpgToolsRoute.ROUTER_NAME,
            initial = GpgToolsPickerRoute,
        ) { backStack ->
            TwoPaneNavigationContent(backStack)
        }
    }
}

@Composable
fun GpgToolsOperationScreen(
    operation: GpgToolsOperation,
) {
    val loadableState = produceGpgToolsState(
        operation = operation,
    )
    GpgToolsOperationScreen(
        operation = operation,
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpgToolsOperationScreen(
    operation: GpgToolsOperation,
    loadableState: Loadable<GpgToolsState>,
) {
    loadableState.getOrNull()
        ?.sideEffects
        ?.filePickerIntentFlow
        ?.let { FilePickerEffect(it) }

    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = textResource(operation.title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val state = loadableState.getOrNull()
            val fabState = if (state != null) {
                FabState(
                    onClick = state.onRun.takeUnless { state.busy },
                    model = state.operation,
                )
            } else {
                null
            }
            rememberUpdatedState(newValue = fabState)
        },
        floatingActionButton = {
            DefaultFab(
                icon = {
                    Icon(operation.icon, null)
                },
                text = {
                    Text(
                        text = stringResource(Res.string.run_action),
                    )
                },
            )
        },
    ) {
        loadableState.fold(
            ifLoading = {
                item {
                    SkeletonSection()
                }
            },
            ifOk = { state ->
                populateGpgToolsContent(state)
            },
        )
    }
}

private fun LazyListScope.populateGpgToolsContent(
    state: GpgToolsState,
) {
    item("mode") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.fieldHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SegmentedButtonGroup(
                tabState = rememberUpdatedState(newValue = state.scope),
                tabs = state.scopes,
                onClick = state.onScopeChange,
                modifier = Modifier.fillMaxWidth(),
                weight = 1f,
            )
            if (state.operation == GpgToolsOperation.SIGN && state.scope == GpgToolsScope.TEXT) {
                SegmentedButtonGroup(
                    tabState = rememberUpdatedState(newValue = state.signMode),
                    tabs = state.signModes,
                    onClick = state.onSignModeChange,
                    modifier = Modifier.fillMaxWidth(),
                    weight = 1f,
                )
            }
            if (state.operation == GpgToolsOperation.VERIFY && state.scope == GpgToolsScope.TEXT) {
                SegmentedButtonGroup(
                    tabState = rememberUpdatedState(newValue = state.verifyMode),
                    tabs = state.verifyModes,
                    onClick = state.onVerifyModeChange,
                    modifier = Modifier.fillMaxWidth(),
                    weight = 1f,
                )
            }
            Spacer(
                modifier = Modifier,
            )
        }
    }

    if (state.showArmor) {
        item("armor") {
            GpgArmorRow(state)
        }
    }

    if (state.scope == GpgToolsScope.TEXT) {
        val showTextSignature = state.operation == GpgToolsOperation.VERIFY &&
                state.verifyMode == GpgToolsVerifyMode.DETACHED
        item("text_input") {
            FlatTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.fieldHorizontalPadding),
                shapeState = if (showTextSignature) ShapeState.START else ShapeState.ALL,
                label = stringResource(state.inputLabel()),
                value = state.inputText,
                maxLines = 14,
                clearButton = true,
            )
        }
        if (showTextSignature) {
            item("text_signature") {
                FlatTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                        .padding(horizontal = Dimens.fieldHorizontalPadding),
                    shapeState = ShapeState.END,
                    label = stringResource(Res.string.gpg_tools_signature_label),
                    value = state.detachedSignatureText,
                    maxLines = 8,
                    clearButton = true,
                )
            }
        }
    } else {
        item("file_input") {
            GpgFileRefRow(
                title = stringResource(Res.string.gpg_tools_select_input_file),
                file = state.inputFile,
                onSelect = state.onSelectInputFile,
                onClear = state.onClearInputFile,
            )
        }
        if (state.operation == GpgToolsOperation.VERIFY) {
            item("signature_input") {
                GpgFileRefRow(
                    title = stringResource(Res.string.gpg_tools_select_signature_file),
                    file = state.signatureFile,
                    onSelect = state.onSelectSignatureFile,
                    onClear = state.onClearSignatureFile,
                )
            }
        }
    }

    populateGpgToolsKeySection(state)

    item("bottom_spacer") {
        Spacer(
            modifier = Modifier.height(16.dp),
        )
    }
}

private fun LazyListScope.populateGpgToolsKeySection(
    state: GpgToolsState,
) {
    when (state.operation) {
        GpgToolsOperation.SIGN -> {
            populateSigningKeySection(state)
        }

        GpgToolsOperation.ENCRYPT -> {
            item("recipient_section") {
                Section(
                    text = stringResource(Res.string.gpg_tools_select_recipients),
                )
            }
            val recipients = state.storedKeys.filter { it.id in state.selectedRecipientIds }
            itemsIndexed(
                items = recipients,
                key = { _, item -> item.id },
            ) { index, key ->
                GpgRecipientRow(
                    keyItem = key,
                    shapeState = getShapeState(recipients, index) { _, _ -> true },
                    enabled = !state.busy,
                    onChoose = state.onChooseRecipients,
                    onRemove = {
                        state.onToggleRecipient(key.id)
                    },
                )
            }
            item("recipient_add") {
                GpgAddKeyButton(
                    title = stringResource(Res.string.gpg_tools_add_recipients),
                    enabled = !state.busy,
                    onClick = state.onChooseRecipients,
                )
            }
            populateCustomPublicKeySection(
                state = state,
                addButtonKey = "custom_public_key_add_encrypt",
                leadingSpacer = state.customPublicKeys.isNotEmpty(),
            )
            populateSigningKeySection(state)
        }

        GpgToolsOperation.VERIFY -> {
            populateCustomPublicKeySection(
                state = state,
                addButtonKey = "custom_public_key_add_verify",
                leadingSpacer = false,
            )
            item("verify_note") {
                VerifyNote()
            }
        }

        GpgToolsOperation.DECRYPT -> {
            populateCustomPublicKeySection(
                state = state,
                addButtonKey = "custom_public_key_add_decrypt",
                leadingSpacer = false,
            )
            item("decrypt_note") {
                DecryptNote()
            }
        }
    }
}

@Composable
private fun DecryptNote() {
    Column(
        modifier = Modifier,
    ) {
        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
        Icon(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = stringResource(Res.string.gpg_tools_decrypt_keys_note),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = stringResource(Res.string.gpg_tools_stored_public_keys_note),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
    }
}

@Composable
private fun VerifyNote() {
    Column(
        modifier = Modifier,
    ) {
        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
        Icon(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = stringResource(Res.string.gpg_tools_stored_public_keys_note),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
    }
}

private fun LazyListScope.populateSigningKeySection(
    state: GpgToolsState,
) {
    val optional = state.operation == GpgToolsOperation.ENCRYPT
    val selectedId = if (optional) state.selectedEncryptSigningKeyId else state.selectedPrivateKeyId
    item("signing_key_section") {
        Section(
            text = stringResource(
                if (optional) Res.string.gpg_tools_sign_encrypted_message
                else Res.string.gpg_tools_select_signing_key,
            ),
        )
    }
    item("signing_key") {
        GpgSigningKeyRow(
            keyItem = state.storedKeys.firstOrNull { it.id == selectedId },
            optional = optional,
            enabled = !state.busy,
            onChoose = state.onChooseSigningKey,
        )
    }
}

private fun LazyListScope.populateCustomPublicKeySection(
    state: GpgToolsState,
    addButtonKey: String,
    leadingSpacer: Boolean,
) {
    val customPublicKeys = state.customPublicKeys
    if (leadingSpacer) {
        item("custom_public_key_spacer_$addButtonKey") {
            Spacer(
                modifier = Modifier.height(8.dp),
            )
        }
    }

    itemsIndexed(
        items = customPublicKeys,
        key = { _, item -> item.id },
    ) { index, key ->
        GpgCustomPublicKeyRow(
            shapeState = getShapeState(customPublicKeys, index) { _, _ -> true },
            onRemove = {
                state.onRemovePublicKey(key.id)
            },
        )
    }
    item(addButtonKey) {
        GpgAddKeyButton(
            title = stringResource(Res.string.gpg_tools_public_key_add),
            onClick = state.onAddPublicKey,
        )
    }
}

@Composable
private fun GpgArmorRow(
    state: GpgToolsState,
) {
    GpgCheckboxRow(
        title = stringResource(Res.string.gpg_tools_armor_label),
        text = null,
        checked = state.armor,
        enabled = true,
        onCheckedChange = state.onArmorChange,
    )
}

@Composable
private fun GpgCheckboxRow(
    title: String,
    text: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val updatedChecked by rememberUpdatedState(checked)
    val updatedOnCheckedChanged by rememberUpdatedState(onCheckedChange)
    FlatItemLayoutExpressive(
        leading = {
            Checkbox(
                checked = checked,
                enabled = enabled,
                onCheckedChange = {
                    updatedOnCheckedChanged(!updatedChecked)
                },
            )
        },
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (text != null) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        onClick = {
            updatedOnCheckedChanged(!updatedChecked)
        },
        enabled = enabled,
    )
}

@Composable
private fun GpgSigningKeyRow(
    keyItem: GpgToolsState.KeyItem?,
    optional: Boolean,
    enabled: Boolean,
    onChoose: () -> Unit,
) {
    FlatItemLayoutExpressive(
        leading = {
            Icon(imageVector = Icons.Outlined.Key, contentDescription = null)
        },
        content = {
            if (keyItem != null) {
                GpgToolsKeyIdentity(key = keyItem)
            } else {
                FlatItemTextContent(
                    title = {
                        Text(
                            stringResource(
                                if (optional) Res.string.gpg_tools_sign_none
                                else Res.string.gpg_tools_choose_signing_key,
                            ),
                        )
                    },
                )
            }
        },
        trailing = { ChevronIcon() },
        onClick = onChoose.takeIf { enabled },
    )
}

@Composable
private fun GpgAddKeyButton(
    title: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        modifier = Modifier
            .padding(top = 8.dp)
            .padding(horizontal = Dimens.buttonHorizontalPadding),
        enabled = enabled,
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(),
        elevation = ButtonDefaults.filledTonalButtonElevation(),
    ) {
        Icon(
            modifier = Modifier.size(ButtonDefaults.IconSize),
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(title)
    }
}

@Composable
private fun GpgRecipientRow(
    keyItem: GpgToolsState.KeyItem,
    shapeState: Int,
    enabled: Boolean,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
) {
    FlatItemLayoutExpressive(
        shapeState = shapeState,
        leading = {
            Icon(imageVector = Icons.Outlined.Key, contentDescription = null)
        },
        content = {
            GpgToolsKeyIdentity(key = keyItem)
        },
        trailing = {
            IconButton(enabled = enabled, onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.RemoveCircleOutline,
                    contentDescription = stringResource(Res.string.remove) + ": " + keyItem.title,
                )
            }
        },
        onClick = onChoose.takeIf { enabled },
    )
}

@Composable
private fun GpgFileRefRow(
    title: String,
    file: GpgToolsState.FileRef?,
    onSelect: () -> Unit,
    onClear: () -> Unit,
) {
    FlatItemLayoutExpressive(
        leading = {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
            )
        },
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            file?.let {
                Text(
                    text = it.name ?: it.uri,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.combineAlpha(MediumEmphasisAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailing = {
            TextButton(
                onClick = if (file == null) onSelect else onClear,
            ) {
                Text(
                    text = stringResource(
                        if (file == null) {
                            Res.string.choose_file
                        } else {
                            Res.string.gpg_tools_clear_file
                        },
                    ),
                )
            }
        },
        onClick = onSelect,
    )
}

@Composable
private fun GpgCustomPublicKeyRow(
    shapeState: Int,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember {
        mutableStateOf(false)
    }

    Box {
        FlatItemLayoutExpressive(
            shapeState = shapeState,
            leading = {
                Icon(
                    imageVector = Icons.Outlined.Key,
                    contentDescription = null,
                )
            },
            content = {
                Text(
                    text = stringResource(Res.string.gpg_tools_pasted_public_key_label),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            onClick = {
                menuExpanded = true
            },
        )
        KeyguardDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = {
                menuExpanded = false
            },
        ) {
            DropdownMenuItem(
                text = {
                    Text(stringResource(Res.string.remove))
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuExpanded = false
                    onRemove()
                },
            )
        }
    }
}

private fun GpgToolsState.inputLabel() = when (operation) {
    GpgToolsOperation.SIGN,
    GpgToolsOperation.ENCRYPT,
        -> Res.string.input

    GpgToolsOperation.VERIFY -> if (verifyMode == GpgToolsVerifyMode.INLINE) {
        Res.string.gpg_tools_signed_text_label
    } else {
        Res.string.input
    }

    GpgToolsOperation.DECRYPT -> Res.string.gpg_tools_encrypted_text_label
}
