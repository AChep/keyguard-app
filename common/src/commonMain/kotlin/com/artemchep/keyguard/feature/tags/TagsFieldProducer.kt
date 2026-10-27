package com.artemchep.keyguard.feature.tags

import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/**
 * Produces a [TagsFieldModel] that persists its chips under
 * `$key.items` and its draft under `$key.draft`.
 *
 * @param catalogFlow the tags that exist in the vault, mapped to
 *   the number of items that have them; see [tagUsage].
 */
fun RememberStateFlowScope.tagsFieldFlow(
    key: String,
    initial: List<String>,
    catalogFlow: Flow<Map<String, Int>>,
    hint: String? = null,
): Flow<TagsFieldModel> {
    val initialTags = addTags(
        current = emptyList(),
        input = initial,
        catalog = emptyMap(),
    )
    val tagsSink = mutablePersistedFlow("$key.items") {
        initialTags
    }
    val draftHandle = textFieldHandle(
        key = "$key.draft",
    )

    return combine(
        tagsSink,
        draftHandle.sink,
        catalogFlow,
    ) { tags, draftCell, catalog ->
        fun commit(text: String) {
            tagsSink.update { current ->
                addTags(
                    current = current,
                    input = splitTags(text),
                    catalog = catalog,
                )
            }
        }

        fun onCommit(text: String) {
            commit(text)
            draftHandle.setText("")
        }

        fun onDraftChange(text: String) {
            val input = splitTagInput(text)
            if (input.tags.isEmpty()) {
                draftHandle.onChange(text)
                return
            }

            commit(input.tags.joinToString(separator = "\n"))
            draftHandle.setText(input.rest)
        }

        fun onRemove(tag: String) {
            tagsSink.update { current ->
                current - tag
            }
        }

        fun onEdit(tag: String) {
            // Do not lose the tag that is being typed.
            commit(draftHandle.sink.value.text)
            onRemove(tag)
            draftHandle.setText(tag)
        }

        val draft = TextFieldModel(
            id = draftHandle.id,
            text = draftCell.text,
            textRevision = draftCell.revision,
            hint = hint,
            onChange = ::onDraftChange,
            onSetText = draftHandle::setText,
        )
        val value = addTags(
            current = tags,
            input = splitTags(draftCell.text),
            catalog = catalog,
        )
        val suggestions = rankTagSuggestions(
            query = draftCell.text,
            selected = tags,
            catalog = catalog,
            boosted = initialTags,
        )
        TagsFieldModel(
            tags = tags.toImmutableList(),
            draft = draft,
            value = value.toImmutableList(),
            suggestions = suggestions.toImmutableList(),
            onCommit = ::onCommit,
            onRemove = ::onRemove,
            onEdit = ::onEdit,
        )
    }
}
