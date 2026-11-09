package com.artemchep.keyguard.feature.p2psync.testing

import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.parts.PartSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builders and edits of email forwarder bodies: `{"name", "type", "data": {key: value}}`. */
object ForwarderBodies {
    fun forwarder(
        name: String,
        type: String = "anonaddy",
        vararg data: Pair<String, String>,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("type", type)
        put("data", dataOf(data.toMap()))
    }

    fun rename(body: JsonObject, name: String): JsonObject = JsonObject(body + ("name" to JsonPrimitive(name)))

    fun retype(body: JsonObject, type: String): JsonObject = JsonObject(body + ("type" to JsonPrimitive(type)))

    /** Returns [body] with the data [key] set to [value], or removed if `null`. */
    fun setData(body: JsonObject, key: String, value: String?): JsonObject {
        val data = data(body).toMutableMap()
        if (value == null) data.remove(key) else data[key] = value
        return JsonObject(body + ("data" to dataOf(data)))
    }

    /** The data of [body] as a map. */
    fun data(body: JsonObject?): Map<String, String> = (body?.get("data") as? JsonObject)
        ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.let { key to it.content } }
        .orEmpty()
        .toMap()

    private fun dataOf(data: Map<String, String>): JsonObject = buildJsonObject {
        data.forEach { (key, value) -> put(key, value) }
    }
}

/** The part schema of the known kind of this entry. */
fun SyncEntry.schema(): PartSchema = requireNotNull(knownKind).schema
