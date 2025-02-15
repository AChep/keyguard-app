package com.artemchep.keyguard.feature.decorator

import com.artemchep.keyguard.platform.recordException

suspend inline fun <Generic, Value> Collection<Value>.forEachWithDecor(
    decorator: ItemDecorator<Generic, Value>,
    onSection: (Generic) -> Unit,
    onItem: (Value) -> Unit,
) {
    forEach { item ->
        val section = decorator.getOrNull(item)
        if (section != null) {
            onSection(section)
        }
        onItem(item)
    }
}

suspend inline fun <Generic, Value, GenericId> Collection<Value>.forEachWithDecorUniqueSectionsOnly(
    decorator: ItemDecorator<Generic, Value>,
    tag: String,
    provideItemId: (Generic) -> GenericId,
    onItem: (Generic) -> Unit,
) where Value : Generic {
    val sectionIds = mutableSetOf<GenericId>()
    forEachWithDecor(
        decorator = decorator,
        onSection = {
            val sectionId = provideItemId(it)
            if (sectionId !in sectionIds) {
                sectionIds += sectionId
                onItem(it)
            } else {
                val sections = sectionIds
                    .joinToString()

                val msg = "Duplicate sections prevented @ $tag: $sections, [$sectionId]"
                val exception = RuntimeException(msg)
                recordException(exception)
            }
        },
        onItem = onItem,
    )
}
