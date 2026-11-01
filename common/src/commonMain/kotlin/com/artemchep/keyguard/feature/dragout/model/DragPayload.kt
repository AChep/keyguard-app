package com.artemchep.keyguard.feature.dragout.model

import com.artemchep.keyguard.common.service.dragout.PreparedContent

/**
 * Everything one drag gesture offers: items, each in one or more representations.
 *
 * A transport takes, per item, the first representation it supports and skips the
 * rest. The payload owns its prepared files until a transport takes them; closing it
 * is idempotent.
 */
class DragPayload(
    val label: String,
    val items: List<DragItem>,
) : AutoCloseable {
    init {
        require(items.isNotEmpty()) { "A drag payload must contain an item" }
    }

    override fun close() {
        items.forEach { item ->
            item.representations.forEach { (it as? DragRepresentation.File)?.content?.close() }
        }
    }
}

class DragItem(
    val representations: List<DragRepresentation>,
) {
    init {
        require(representations.isNotEmpty()) { "A drag item must have a representation" }
    }

    constructor(representation: DragRepresentation) : this(listOf(representation))
}

sealed interface DragRepresentation {
    /** Verified bytes offered as a named file, never through a Keyguard-owned plaintext path. */
    class File(val content: PreparedContent) : DragRepresentation

    /** An absolute http(s) link. Not a secret: transports may expose it to every app. */
    class Url(val url: String) : DragRepresentation
}

inline fun <reified T : DragRepresentation> DragItem.representation(): T? =
    representations.firstNotNullOfOrNull { it as? T }

inline fun <reified T : DragRepresentation> DragPayload.representations(): List<T> =
    items.mapNotNull { it.representation<T>() }
