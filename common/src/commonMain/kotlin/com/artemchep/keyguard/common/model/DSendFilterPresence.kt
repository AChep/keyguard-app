package com.artemchep.keyguard.common.model

/**
 * A per-attribute presence index of a list of [DSend]s, built in a single
 * pass. It lets [DSendFilter.Primitive.existsIn] answer "does any send in the
 * list match this primitive?" via set membership.
 */
data class DSendFilterPresence(
    val accountIds: Set<String?>,
    val types: Set<DSend.Type>,
) {
    companion object {
        fun <T> of(
            items: List<T>,
            getter: (T) -> DSend,
        ): DSendFilterPresence {
            val accountIds = mutableSetOf<String?>()
            val types = mutableSetOf<DSend.Type>()
            items.forEach { item ->
                val send = getter(item)
                accountIds += send.accountId
                types += send.type
            }
            return DSendFilterPresence(
                accountIds = accountIds,
                types = types,
            )
        }
    }
}

fun DSendFilter.Primitive.existsIn(
    presence: DSendFilterPresence,
): Boolean? = when (this) {
    is DSendFilter.ById -> when (what) {
        DSendFilter.ById.What.ACCOUNT -> id in presence.accountIds
    }

    is DSendFilter.ByType -> type in presence.types
}
