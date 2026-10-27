package com.artemchep.keyguard.feature.tags

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * A list of tags edited as chips, plus the text of
 * the tag that is being typed.
 */
@Immutable
data class TagsFieldModel(
    val tags: ImmutableList<String>,
    val draft: TextFieldModel,
    /**
     * The tags to save: the [tags] plus the
     * [draft], if it is not blank.
     */
    val value: ImmutableList<String> = tags,
    val suggestions: ImmutableList<TagSuggestion> = persistentListOf(),
    /**
     * Adds the tags from a text, clearing the draft.
     * `null` if the field is read-only.
     */
    val onCommit: ((String) -> Unit)? = null,
    val onRemove: ((String) -> Unit)? = null,
    /** Removes the tag and moves its text into the draft. */
    val onEdit: ((String) -> Unit)? = null,
) {
    companion object {
        val empty = TagsFieldModel(
            tags = persistentListOf(),
            draft = TextFieldModel.empty,
        )
    }
}
