package com.artemchep.keyguard.feature.p2psync.parts

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Reads and writes the value of a part in a body.
 *
 * The value of a one-path part is the JSON value at the path. The value of a part with
 * several paths is an object from each present path (in its text form) to its value. A
 * missing value, a JSON `null` and an absent path all read as `null`; writing `null`
 * removes the paths. Objects left empty by a removal are removed too.
 */
object PartAccess {
    private val emptyObject = JsonObject(emptyMap())

    /** Returns the value of [spec] in [body], or `null` if none of its paths is present. */
    fun get(body: JsonObject, spec: PartSpec): JsonElement? = if (spec.paths.size == 1) {
        body.getAt(spec.paths.single().segments)
    } else {
        val values = spec.paths.mapNotNull { path ->
            body.getAt(path.segments)?.let { path.toString() to it }
        }
        values.takeIf { it.isNotEmpty() }?.let { JsonObject(it.toMap()) }
    }

    /** Returns [body] with the value of [spec] replaced by [value]. */
    fun set(body: JsonObject, spec: PartSpec, value: JsonElement?): JsonObject =
        body.setPart(spec, value).pruneAncestors(spec.paths)

    /**
     * Returns [base] with every part of [schema] set from [values] (a missing key removes
     * the part). The result depends only on the remainder of [base] and on [values].
     */
    fun compose(base: JsonObject, schema: PartSchema, values: Map<String, JsonElement?>): JsonObject =
        schema.parts
            .fold(base) { acc, spec -> acc.setPart(spec, values[spec.key]) }
            .pruneAncestors(schema.paths)

    /** Returns [body] without any of the parts of [schema]: what follows the entry as a whole. */
    fun remainder(body: JsonObject, schema: PartSchema): JsonObject = compose(body, schema, emptyMap())

    /** Returns this body with the value of [spec] replaced by [value], without pruning. */
    private fun JsonObject.setPart(spec: PartSpec, value: JsonElement?): JsonObject = if (spec.paths.size == 1) {
        setAt(spec.paths.single().segments, value)
    } else {
        val values = value as? JsonObject
        spec.paths.fold(this) { acc, path -> acc.setAt(path.segments, values?.get(path.toString())) }
    }

    private fun JsonObject.getAt(segments: List<String>): JsonElement? {
        var current: JsonElement? = this
        for (segment in segments) {
            current = (current as? JsonObject)?.get(segment)
        }
        return current.takeUnless { it is JsonNull }
    }

    private fun JsonObject.setAt(segments: List<String>, value: JsonElement?): JsonObject {
        val head = segments.first()
        val leaf = segments.size == 1
        val child = this[head] as? JsonObject
        val untouched = !leaf && child == null && value == null
        return if (untouched) {
            this
        } else {
            val next = if (leaf) value else (child ?: emptyObject).setAt(segments.drop(1), value)
            if (next == null) JsonObject(this - head) else JsonObject(this + (head to next))
        }
    }

    /** Removes the empty objects among the ancestors of [paths], deepest first. */
    private fun JsonObject.pruneAncestors(paths: List<JsonPointer>): JsonObject {
        val ancestors = paths
            .flatMap { path -> (1 until path.segments.size).map { path.segments.take(it) } }
            .distinct()
            .sortedByDescending { it.size }
        return ancestors.fold(this) { acc, ancestor ->
            val value = acc.getAt(ancestor)
            if (value is JsonObject && value.isEmpty()) acc.setAt(ancestor, null) else acc
        }
    }
}
