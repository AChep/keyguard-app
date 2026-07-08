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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.artemchep.keyguard.res.gpg_tools_armor_label
import com.artemchep.keyguard.res.gpg_tools_clear_file
import com.artemchep.keyguard.res.gpg_tools_decrypt_keys_note
import com.artemchep.keyguard.res.gpg_tools_encrypted_text_label
import com.artemchep.keyguard.res.gpg_tools_no_gpg_keys
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
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.KeyguardDropdownMenu
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.SimpleNote
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
                    Icon(Icons.Outlined.Check, null)
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
            val publicKeys = state.storedKeys.filter { it.publicKeyAvailable }
            itemsIndexed(
                items = publicKeys,
                key = { _, item -> item.id },
            ) { index, key ->
                GpgRecipientRow(
                    keyItem = key,
                    shapeState = getShapeState(publicKeys, index) { _, _ -> true },
                    selected = key.id in state.selectedRecipientIds,
                    onToggle = {
                        state.onToggleRecipient(key.id)
                    },
                )
            }
            populateCustomPublicKeySection(
                state = state,
                addButtonKey = "custom_public_key_add_encrypt",
                leadingSpacer = publicKeys.isNotEmpty() && state.customPublicKeys.isNotEmpty(),
            )
            item("encrypt_signing_section") {
                Section(
                    text = stringResource(Res.string.gpg_tools_sign_encrypted_message),
                )
            }
            item("encrypt_no_sign") {
                GpgOptionalSigningNoneRow(
                    selected = state.selectedEncryptSigningKeyId == null,
                    onSelect = {
                        state.onSelectEncryptSigningKey(null)
                    },
                )
            }
            val signingKeys = state.storedKeys.filter { it.canSign }
            items(
                items = signingKeys,
                key = { "encrypt_sign_${it.id}" },
            ) { key ->
                GpgPrivateKeyRow(
                    keyItem = key,
                    selected = state.selectedEncryptSigningKeyId == key.id,
                    onSelect = {
                        state.onSelectEncryptSigningKey(key.id)
                    },
                )
            }
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
    item("signing_key_section") {
        Section(
            text = stringResource(Res.string.gpg_tools_select_signing_key),
        )
    }
    val signingKeys = state.storedKeys.filter { it.canSign }
    if (signingKeys.isEmpty()) {
        item("no_signing_keys") {
            FlatSimpleNote(
                type = SimpleNote.Type.WARNING,
                text = stringResource(Res.string.gpg_tools_no_gpg_keys),
            )
        }
    } else {
        items(
            items = signingKeys,
            key = { it.id },
        ) { key ->
            GpgPrivateKeyRow(
                keyItem = key,
                selected = state.selectedPrivateKeyId == key.id,
                onSelect = {
                    state.onSelectPrivateKey(key.id)
                },
            )
        }
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
        GpgAddPublicKeyButton(
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
private fun GpgPrivateKeyRow(
    keyItem: GpgToolsState.KeyItem,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    FlatItemLayoutExpressive(
        leading = {
            RadioButton(
                selected = selected,
                onClick = onSelect,
            )
        },
        content = {
            Text(
                text = keyItem.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!keyItem.description.isNullOrBlank()) {
                Text(
                    text = keyItem.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        onClick = onSelect,
    )
}

@Composable
private fun GpgOptionalSigningNoneRow(
    selected: Boolean,
    onSelect: () -> Unit,
) {
    FlatItemLayoutExpressive(
        leading = {
            RadioButton(
                selected = selected,
                onClick = onSelect,
            )
        },
        content = {
            Text(
                text = stringResource(Res.string.gpg_tools_sign_none),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = onSelect,
    )
}

@Composable
private fun GpgRecipientRow(
    keyItem: GpgToolsState.KeyItem,
    shapeState: Int = ShapeState.ALL,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    FlatItemLayoutExpressive(
        shapeState = shapeState,
        leading = {
            Checkbox(
                checked = selected,
                onCheckedChange = {
                    onToggle()
                },
            )
        },
        content = {
            Text(
                text = keyItem.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!keyItem.description.isNullOrBlank()) {
                Text(
                    text = keyItem.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        onClick = onToggle,
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
private fun GpgAddPublicKeyButton(
    onClick: () -> Unit,
) {
    Button(
        modifier = Modifier
            .padding(top = 8.dp)
            .padding(horizontal = Dimens.buttonHorizontalPadding),
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(),
        elevation = ButtonDefaults.filledTonalButtonElevation(),
    ) {
        Icon(
            modifier = Modifier
                .size(ButtonDefaults.IconSize),
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
        )
        Spacer(
            modifier = Modifier
                .width(ButtonDefaults.IconSpacing),
        )
        Text(
            text = stringResource(Res.string.gpg_tools_public_key_add),
        )
    }
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
