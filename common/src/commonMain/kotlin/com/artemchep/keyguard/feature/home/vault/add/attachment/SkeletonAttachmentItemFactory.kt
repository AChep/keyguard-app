package com.artemchep.keyguard.feature.home.vault.add.attachment

import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.model.create.CreateSendRequest
import com.artemchep.keyguard.common.model.create.attachments
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.add.AddStateItem
import com.artemchep.keyguard.feature.add.LocalStateItem
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.home.vault.add.Foo2Factory
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map

class SkeletonAttachmentItemFactory : Foo2Factory<AddStateItem.Attachment<*>, SkeletonAttachment> {
    override val type: String = "attachment"

    override fun RememberStateFlowScope.release(key: String) {
        clearPersistedFlow("$key.identity")
        clearPersistedFlow("$key.name")
    }

    override fun RememberStateFlowScope.add(
        key: String,
        initial: SkeletonAttachment?,
    ): AddStateItem.Attachment<CreateRequest> {
        val identitySink = mutablePersistedFlow("$key.identity") {
            val identity = initial?.identity
            requireNotNull(identity)
        }
        val identity = identitySink.value
        val synced = identity is SkeletonAttachment.Remote.Identity

        val nameKey = "$key.name"
        val nameSink = mutablePersistedFlow(nameKey) {
            initial?.name.orEmpty()
        }
        val nameMutableState = asComposeState<String>(nameKey)

        val textFlow = nameSink
            .map { uri ->
                TextFieldModel2(
                    text = uri,
                    state = nameMutableState,
                    onChange = nameMutableState::value::set,
                )
            }
        val stateFlow = textFlow
            .map { text ->
                AddStateItem.Attachment.State(
                    id = "id",
                    name = text,
                    size = initial?.size,
                    synced = synced,
                )
            }
            .persistingStateIn(
                scope = screenScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = AddStateItem.Attachment.State(
                    id = "id",
                    name = TextFieldModel2.empty,
                    size = null,
                    synced = synced,
                ),
            )
        return AddStateItem.Attachment(
            id = key,
            state = LocalStateItem(
                flow = stateFlow,
                populator = { state ->
                    CreateRequest.attachments.modify(this) {
                        val model = when (identity) {
                            is SkeletonAttachment.Remote.Identity -> {
                                CreateRequest.Attachment.Remote(
                                    id = identity.id,
                                    name = state.name.text,
                                )
                            }

                            is SkeletonAttachment.Local.Identity -> {
                                CreateRequest.Attachment.Local(
                                    id = identity.id,
                                    uri = identity.uri,
                                    size = identity.size,
                                    name = state.name.text,
                                )
                            }
                        }
                        it.add(model)
                    }
                },
            ),
        )
    }
}
