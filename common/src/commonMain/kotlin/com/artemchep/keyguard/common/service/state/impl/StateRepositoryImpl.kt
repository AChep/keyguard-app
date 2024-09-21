package com.artemchep.keyguard.common.service.state.impl

import com.artemchep.keyguard.common.model.AnyMap
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.getObject
import com.artemchep.keyguard.common.service.state.StateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class StateRepositoryImpl(
    private val store: KeyValueStore,
    private val json: Json,
) : StateRepository {
    private val registry = mutableMapOf<String, KeyValuePreference<AnyMap?>>()

    private fun createOrGetPref(
        key: String,
    ): KeyValuePreference<AnyMap?> = synchronized(this) {
        registry.getOrPut(key) {
            createPref(key)
        }
    }

    private fun createPref(
        key: String,
    ): KeyValuePreference<AnyMap?> = store.getObject(
        key = key,
        defaultValue = null,
        serialize = { entity ->
            val obj = entity?.value.toJson()
            obj.let(json::encodeToString)
        },
        deserialize = { text ->
            kotlin.runCatching {
                val obj = json.decodeFromString<JsonObject>(text)
                val map = obj.toMap()
                AnyMap(map)
            }.getOrNull()
        },
    )

    override fun put(key: String, model: Map<String, Any?>) = kotlin.run {
        val anyMap = AnyMap(
            value = model,
        )
        createOrGetPref(key)
            .setAndCommit(anyMap)
    }

    override fun get(key: String): Flow<Map<String, Any?>> = createOrGetPref(key)
        .map {
            it?.value.orEmpty()
        }
}

fun JsonObject.toMap(): Map<String, Any?> = this
    .mapValues { (_, element) ->
        element.extractedContent
    }

fun Any?.toSchema(): JsonElement {
    return when (this) {
        null -> JsonNull
        is String -> JsonPrimitive("string")
        is Number -> JsonPrimitive("number")
        is Boolean -> JsonPrimitive("boolean")
        is Map<*, *> -> {
            val content = map { (k, v) -> k.toString() to v.toSchema() }
            JsonObject(content.toMap())
        }

        is List<*> -> {
            val content = map { it.toSchema() }
            JsonArray(content)
        }

        is JsonElement -> JsonPrimitive(this::class.qualifiedName)
        else -> JsonPrimitive("unknown:" + this::class.qualifiedName)
    }
}

fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> {
        val content = map { (k, v) -> k.toString() to v.toJson() }
        JsonObject(content.toMap())
    }

    is List<*> -> {
        val content = map { it.toJson() }
        JsonArray(content)
    }

    is JsonElement -> this
    else -> throw IllegalArgumentException("Unable to encode $this")
}

val JsonElement.extractedContent: Any?
    get() {
        if (this is JsonPrimitive) {
            if (this.jsonPrimitive.isString) {
                return this.jsonPrimitive.content
            }
            return this.jsonPrimitive.booleanOrNull
                ?: this.jsonPrimitive.longOrNull
                ?: this.jsonPrimitive.doubleOrNull
                ?: this.jsonPrimitive.contentOrNull
        }
        if (this is JsonArray) {
            return this.jsonArray.map {
                it.extractedContent
            }
        }
        if (this is JsonObject) {
            return this.jsonObject.entries.associate {
                it.key to it.value.extractedContent
            }
        }
        return null
    }
