@file:Suppress("ConvertArgumentToSet")

package app.keemobile.kotpass.models

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue

/**
 * Wraps [Map] to override [equals] and take into
 * account order of items in equality checks.
 */
class EntryFields(
    private val fields: Map<String, EntryValue>
) : Map<String, EntryValue> by fields {
    val title
        get() = fields[BasicField.Title()]
    val userName
        get() = fields[BasicField.UserName()]
    val password
        get() = fields[BasicField.Password()]
    val url
        get() = fields[BasicField.Url()]
    val notes
        get() = fields[BasicField.Notes()]

    operator fun get(key: BasicField): EntryValue? = fields[key()]

    /**
     * Returns a new [EntryFields] with entries having the keys of [fields] and the values
     * obtained by applying the [transform] function to each entry.
     */
    fun mapValues(transform: (Map.Entry<String, EntryValue>) -> EntryValue) =
        EntryFields(fields.mapValues(transform))

    /**
     * Returns a new [EntryFields] with entries having the keys obtained by applying
     * the [transform] function to each entry and the values of [fields].
     *
     * In case if any two entries are mapped to the equal keys, the value of the latter one
     * will overwrite the value associated with the former one.
     */
    fun mapKeys(transform: (Map.Entry<String, EntryValue>) -> String) =
        EntryFields(fields.mapKeys(transform))

    /**
     * Returns [EntryFields] containing all key-value pairs with keys matching the given [predicate].
     */
    fun filterKeys(predicate: (String) -> Boolean) =
        EntryFields(fields.filterKeys(predicate))

    /**
     * Returns [EntryFields] containing all key-value pairs with values matching the given [predicate].
     */
    fun filterValues(predicate: (EntryValue) -> Boolean) =
        EntryFields(fields.filterValues(predicate))

    /**
     * Returns a new [EntryFields] containing all key-value pairs matching the given [predicate].
     */
    fun filter(predicate: (Map.Entry<String, EntryValue>) -> Boolean) =
        EntryFields(fields.filter(predicate))

    /**
     * Returns a new [EntryFields] containing all key-value pairs not matching the given [predicate].
     */
    fun filterNot(predicate: (Map.Entry<String, EntryValue>) -> Boolean) =
        EntryFields(fields.filterNot(predicate))

    /**
     * Creates a new [EntryFields] by replacing or adding an entry from a given key-value [pair].
     * The [pair] is iterated in the end if it has a unique key.
     */
    operator fun plus(pair: Pair<String, EntryValue>) = EntryFields(fields + pair)

    /**
     * Creates a new [EntryFields] by replacing or adding entries from a given collection of key-value [pairs].
     * Those [pairs] with unique keys are iterated in the end in the order of [pairs] collection.
     */
    operator fun plus(pairs: Iterable<Pair<String, EntryValue>>) = EntryFields(fields + pairs)

    /**
     * Creates a new [EntryFields] by replacing or adding entries from a given array of key-value [pairs].
     * Those [pairs] with unique keys are iterated in the end in the order of [pairs] array.
     */
    operator fun plus(pairs: Array<out Pair<String, EntryValue>>) = EntryFields(fields + pairs)

    /**
     * Creates a new [EntryFields] by replacing or adding entries from a given sequence of key-value [pairs].
     * Those [pairs] with unique keys are iterated in the end in the order of [pairs] sequence.
     */
    operator fun plus(pairs: Sequence<Pair<String, EntryValue>>) = EntryFields(fields + pairs)

    /**
     * Creates a new [EntryFields] by replacing or adding entries from another [map].
     * Those entries of another [map] that are missing in this map are iterated in the end in the order of that [map].
     */
    operator fun plus(map: Map<String, EntryValue>) = EntryFields(fields + map)

    /**
     * Returns [EntryFields] containing all entries of the original except the entry with the given [key].
     */
    operator fun minus(key: String) = EntryFields(fields - key)

    /**
     * Returns [EntryFields] containing all entries of the original except those entries
     * the keys of which are contained in the given [keys] collection.
     */
    operator fun minus(keys: Iterable<String>) = EntryFields(fields - keys)

    /**
     * Returns [EntryFields] containing all entries of the original except those entries
     * the keys of which are contained in the given [keys] array.
     */
    operator fun minus(keys: Array<String>) = EntryFields(fields - keys)

    /**
     * Returns [EntryFields] containing all entries of the original except those entries
     * the keys of which are contained in the given [keys] sequence.
     */
    operator fun minus(keys: Sequence<String>) = EntryFields(fields - keys)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EntryFields) return false

        val iterator = fields.iterator()
        val otherIterator = other.iterator()

        while (true) {
            val diff = iterator.hasNext() xor otherIterator.hasNext()
            if (diff) return false

            if (iterator.hasNext()) {
                if (iterator.next() != otherIterator.next()) {
                    return false
                }
            } else {
                break
            }
        }
        return true
    }

    override fun hashCode(): Int = fields.hashCode()

    companion object {
        /**
         * Returns a new [EntryFields] with the specified contents, given as a list
         * of pairs where the first value is the key and the second is the value.
         *
         * If multiple pairs have the same key, the resulting map will contain
         * the value from the last of those pairs.
         *
         * Entries are iterated in the order they were specified.
         */
        fun of(vararg pairs: Pair<String, EntryValue>) = EntryFields(mapOf(*pairs))

        /**
         * Creates [EntryFields] which is populated with empty [BasicField]
         * values as required by KeePass contract.
         */
        fun createDefault() = EntryFields(
            buildMap {
                BasicField
                    .entries
                    .filter { it != BasicField.Password }
                    .forEach { field -> put(field(), EntryValue.Plain("")) }

                val password = EncryptedValue.fromString("")
                put(BasicField.Password(), EntryValue.Encrypted(password))
            }
        )
    }
}
