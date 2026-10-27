package com.artemchep.keyguard.feature.confirmation.tags

import com.artemchep.keyguard.feature.tags.TagsFieldModel

data class TagsConfirmationState(
    val tags: TagsFieldModel = TagsFieldModel.empty,
    val onDeny: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
)
