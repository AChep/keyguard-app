package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder

/** A drag could not be prepared or handed to the platform; [title] names what failed. */
class DragOutException(
    cause: Throwable,
    override val title: TextHolder,
) : Exception("Failed to prepare a drag", cause), Readable {
    /** Why it failed, when the cause says. */
    override val text: TextHolder? = when (cause) {
        is Readable -> cause.title
        else -> cause.readableMessageOrNull()?.let(TextHolder::Value)
    }
}
