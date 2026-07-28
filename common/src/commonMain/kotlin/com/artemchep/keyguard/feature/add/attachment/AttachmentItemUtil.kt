package com.artemchep.keyguard.feature.add.attachment

import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.add.AddStateItem
import com.artemchep.keyguard.feature.add.LocalStateItem
import com.artemchep.keyguard.feature.auth.common.TextCell
import com.artemchep.keyguard.feature.auth.common.TextFieldHandle
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine

internal data class AttachmentItemStateConfig(
    val id: String,
    val size: String? = null,
    val synced: Boolean,
    val editable: Boolean = true,
)

internal fun attachmentNameTextField(
    cell: TextCell,
    editable: Boolean,
    handle: TextFieldHandle,
    validated: Validated<String> = Validated.Success(
        model = cell.text,
    ),
): TextFieldModel = if (editable) {
    TextFieldModel.of(
        cell = cell,
        handle = handle,
        validated = validated,
    )
} else {
    TextFieldModel(
        text = cell.text,
        textRevision = cell.revision,
        onChange = null,
    )
}

internal fun attachmentState(
    name: TextFieldModel,
    config: AttachmentItemStateConfig,
): AddStateItem.Attachment.State = AddStateItem.Attachment.State(
    id = config.id,
    name = name,
    size = config.size,
    synced = config.synced,
)

internal fun <Request, Identity> attachmentStatePopulator(
    identity: Identity,
    populator: Request.(Identity, String) -> Request,
): Request.(AddStateItem.Attachment.State) -> Request = { state ->
    populator(identity, state.name.text)
}

context(stateScope: RememberStateFlowScope)
internal fun <Request> createAttachmentStateItem(
    key: String,
    initialName: String,
    nameHandle: TextFieldHandle? = null,
    initialConfig: AttachmentItemStateConfig,
    configFlow: Flow<AttachmentItemStateConfig>,
    validateName: (String) -> Validated<String> = {
        Validated.Success(
            model = it,
        )
    },
    populator: Request.(AddStateItem.Attachment.State) -> Request,
): LocalStateItem<AddStateItem.Attachment.State, Request> = with(stateScope) {
    val nameKey = "$key.name"
    val resolvedNameHandle = nameHandle
        ?: textFieldHandle(nameKey, initial = initialName)
    val initialStateCell = resolvedNameHandle.sink.value

    val stateFlow = combine(
        resolvedNameHandle.sink,
        configFlow,
    ) { cell, config ->
        attachmentState(
            name = attachmentNameTextField(
                cell = cell,
                editable = config.editable,
                handle = resolvedNameHandle,
                validated = validateName(cell.text),
            ),
            config = config,
        )
    }
        .persistingStateIn(
            scope = screenScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = attachmentState(
                name = attachmentNameTextField(
                    cell = initialStateCell,
                    editable = initialConfig.editable,
                    handle = resolvedNameHandle,
                    validated = validateName(initialStateCell.text),
                ),
                config = initialConfig,
            ),
        )

    return LocalStateItem(
        flow = stateFlow,
        populator = populator,
    )
}
