package com.artemchep.keyguard.common.service.imports.mapping

/**
 * The values of one source item, in source order. The values read into a
 * member of the item are taken out; the [rest] become custom fields, so
 * nothing the source carries is lost or written twice.
 */
internal class ImportFieldBag<T>(
    values: Iterable<T>,
    private val keyOf: (T) -> String?,
) {
    private val values = values.toMutableList()

    fun has(key: String): Boolean = values.any { keyOf(it) == key }

    /** The first value with [key], left in the bag. */
    operator fun get(key: String): T? = values.firstOrNull { keyOf(it) == key }

    /** Takes the first value with [key]. */
    fun take(key: String): T? = takeFirst { keyOf(it) == key }

    fun takeFirst(predicate: (T) -> Boolean): T? {
        val index = values.indexOfFirst(predicate)
        return if (index >= 0) values.removeAt(index) else null
    }

    fun takeAll(key: String): List<T> {
        val taken = values.filter { keyOf(it) == key }
        values.removeAll { keyOf(it) == key }
        return taken
    }

    /** Replaces the first value with [key] in place. */
    fun update(key: String, transform: (T) -> T) {
        val index = values.indexOfFirst { keyOf(it) == key }
        if (index >= 0) {
            values[index] = transform(values[index])
        }
    }

    /** The values not taken, in source order, as [transform] maps them. */
    fun <R : Any> rest(transform: (T) -> R?): List<R> = values.mapNotNull(transform)
}
