package com.artemchep.keyguard.feature.add.attachment

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.add.AddStateItem
import com.artemchep.keyguard.feature.add.LocalStateItem
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine

internal data class AttachmentItemStateConfig(
    val id: String,
    val size: String? = null,
    val synced: Boolean,
    val editable: Boolean = true,
)

internal fun attachmentNameTextField(
    name: String,
    editable: Boolean,
    state: MutableState<String>,
    validated: Validated<String> = Validated.Success(
        model = name,
    ),
): TextFieldModel2 = if (editable) {
    TextFieldModel2.of(
        state = state,
        validated = validated,
        onChange = state::value::set,
    )
} else {
    TextFieldModel2(
        state = mutableStateOf(name),
        onChange = null,
    )
}

internal fun attachmentState(
    name: TextFieldModel2,
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
    nameSink: MutableStateFlow<String>? = null,
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
    val resolvedNameSink = nameSink
        ?: mutablePersistedFlow(nameKey) {
        initialName
    }
    val nameState = mutableComposeState(resolvedNameSink)
    val initialStateName = resolvedNameSink.value

    val stateFlow = combine(
        resolvedNameSink,
        configFlow,
    ) { name, config ->
        attachmentState(
            name = attachmentNameTextField(
                name = name,
                editable = config.editable,
                state = nameState,
                validated = validateName(name),
            ),
            config = config,
        )
    }
        .persistingStateIn(
            scope = screenScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = attachmentState(
                name = attachmentNameTextField(
                    name = initialStateName,
                    editable = initialConfig.editable,
                    state = nameState,
                    validated = validateName(initialStateName),
                ),
                config = initialConfig,
            ),
        )

    return LocalStateItem(
        flow = stateFlow,
        populator = populator,
    )
}
