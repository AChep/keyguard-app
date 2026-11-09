package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.P2pJson
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical JSON: object keys sorted by UTF-16 code units, no whitespace and minimal string
 * escaping. Equal values always encode to equal bytes, on every platform, so hashes and
 * signatures over it are stable.
 *
 * The only numbers are integers within ±[MAX_SAFE_INTEGER], in their one textual form: no
 * sign on zero, no leading zeros, no fraction and no exponent. Every JSON parser reads them
 * exactly. Any other number, or an unquoted literal other than `true`, `false` and `null`,
 * has no canonical form: encoding it throws [IllegalArgumentException].
 *
 * Unpaired surrogates are escaped, so text that is not valid UTF-16 encodes the same on
 * every platform.
 */
object CanonicalJson {
    /** The largest magnitude of a number, 2^53 − 1: the largest integer a double holds exactly. */
    const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L

    /** Encodes [element] as canonical JSON text. */
    fun encode(element: JsonElement): String = buildString { appendCanonical(element) }

    /** Encodes [value] as canonical JSON text. */
    fun <T> encode(serializer: SerializationStrategy<T>, value: T): String =
        encode(P2pJson.encodeToJsonElement(serializer, value))

    /** Encodes [element] as canonical JSON UTF-8 bytes. */
    fun encodeToByteArray(element: JsonElement): ByteArray = encode(element).encodeToByteArray()

    /** Encodes [value] as canonical JSON UTF-8 bytes. */
    fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray =
        encode(serializer, value).encodeToByteArray()

    /**
     * `true` if [element] has a canonical form: every number in it is a canonical integer,
     * and no string, key or value, holds an unpaired surrogate. UTF-8 can not carry one, so
     * a store or a message would change it, and its signature would no longer verify.
     */
    fun isCanonical(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.all { (key, value) -> isWellFormed(key) && isCanonical(value) }
        is JsonArray -> element.all(::isCanonical)
        is JsonPrimitive -> when {
            element.isString -> isWellFormed(element.content)
            else -> !element.isNumber() || isCanonicalNumber(element.content)
        }
    }

    /**
     * Checks that [element] has a canonical form, see [isCanonical].
     *
     * @throws IllegalArgumentException if it does not.
     */
    fun requireCanonical(element: JsonElement) {
        require(isCanonical(element)) {
            "The JSON holds a number without a canonical form, or an unpaired surrogate"
        }
    }
}

private fun JsonPrimitive.isNumber(): Boolean =
    this !is JsonNull && !isString && content != "true" && content != "false"

private fun StringBuilder.appendCanonical(element: JsonElement) {
    when (element) {
        is JsonObject -> {
            append('{')
            element.keys.sorted().forEachIndexed { index, key ->
                if (index > 0) append(',')
                appendJsonString(key)
                append(':')
                appendCanonical(element.getValue(key))
            }
            append('}')
        }

        is JsonArray -> {
            append('[')
            element.forEachIndexed { index, item ->
                if (index > 0) append(',')
                appendCanonical(item)
            }
            append(']')
        }

        is JsonPrimitive -> when {
            element is JsonNull -> append("null")
            element.isString -> appendJsonString(element.content)
            element.isNumber() -> {
                require(isCanonicalNumber(element.content)) { "The number '${element.content}' has no canonical form" }
                append(element.content)
            }

            else -> append(element.content)
        }
    }
}

private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_DIGITS = 4

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEachIndexed { index, char ->
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\b' -> append("\\b")
            char == '\u000C' -> append("\\f")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            // UTF-8 encoders replace an unpaired surrogate, each platform with other bytes.
            char < ' ' || isUnpairedSurrogate(value, index) ->
                append("\\u").append(char.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0'))

            else -> append(char)
        }
    }
    append('"')
}

/** `true` if [value] holds no unpaired surrogate. */
private fun isWellFormed(value: String): Boolean = value.indices.none { isUnpairedSurrogate(value, it) }

private fun isUnpairedSurrogate(value: String, index: Int): Boolean {
    val char = value[index]
    return when {
        char.isHighSurrogate() -> value.getOrNull(index + 1)?.isLowSurrogate() != true
        char.isLowSurrogate() -> value.getOrNull(index - 1)?.isHighSurrogate() != true
        else -> false
    }
}

private val integerRegex = Regex("""-?(0|[1-9]\d{0,15})""")

/** `true` if [text] is an integer within ±[CanonicalJson.MAX_SAFE_INTEGER] in its canonical form. */
internal fun isCanonicalNumber(text: String): Boolean = text != "-0" &&
    integerRegex.matches(text) &&
    text.toLongOrNull()?.let { it in -CanonicalJson.MAX_SAFE_INTEGER..CanonicalJson.MAX_SAFE_INTEGER } == true
