package com.artemchep.keyguard.feature.confirmation.tags

import androidx.compose.runtime.Composable
import arrow.core.andThen
import arrow.core.partially1
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.combineToList
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.uuid.Uuid

@Composable
fun tagsConfirmationState(
    args: TagsConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<TagsConfirmationResult>,
): TagsConfirmationState = with(localDI().direct) {
    tagsConfirmationState(
        args = args,
        transmitter = transmitter,
        windowCoroutineScope = instance(),
    )
}

@Composable
fun tagsConfirmationState(
    args: TagsConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<TagsConfirmationResult>,
    windowCoroutineScope: WindowCoroutineScope,
): TagsConfirmationState = produceScreenState(
    key = "tags_confirmation",
    initial = TagsConfirmationState(),
    args = arrayOf(
        windowCoroutineScope,
    ),
) {
    val tagHint = translate(Res.string.tag_value)

    val initialTagsById = args.initialTags
        .associateBy { Uuid.random().toString() }
    val itemIdsSink = mutablePersistedFlow("item_ids") {
        initialTagsById.keys
    }

    fun itemKey(id: String) = "item.$id.text"

    fun removeItem(id: String) {
        itemIdsSink.value = itemIdsSink.value - id
        clearPersistedFlow(itemKey(id))
    }

    fun createItemFlow(
        id: String,
    ) = kotlin.run {
        val sink = mutablePersistedFlow(itemKey(id)) {
            initialTagsById[id].orEmpty()
        }
        val state = mutableComposeState(sink)
        sink.map { text ->
            TagsConfirmationState.Item(
                key = id,
                field = TextFieldModel2(
                    state = state,
                    text = text,
                    hint = tagHint,
                    onChange = state::value::set,
                ),
                onRemove = ::removeItem
                    .partially1(id),
            )
        }
    }

    val itemsFlow = itemIdsSink
        .flatMapLatest { ids ->
            ids
                .map(::createItemFlow)
                .combineToList()
        }

    val tagsFlow = itemIdsSink
        .flatMapLatest { ids ->
            ids
                .map { id ->
                    mutablePersistedFlow(itemKey(id)) {
                        initialTagsById[id].orEmpty()
                    }
                }
                .combineToList()
        }

    combine(
        itemsFlow,
        tagsFlow,
    ) { items, tags ->
            val onConfirm = transmitter
                .partially1(
                    TagsConfirmationResult.Confirm(
                        tags = tags,
                    ),
                )
                .andThen {
                    navigatePopSelf()
                }
            TagsConfirmationState(
                items = items,
                onAdd = {
                    val id = Uuid.random().toString()
                    itemIdsSink.value = itemIdsSink.value + id
                },
                onDeny = {
                    transmitter(TagsConfirmationResult.Deny)
                    navigatePopSelf()
                },
                onConfirm = onConfirm,
            )
        }
}
