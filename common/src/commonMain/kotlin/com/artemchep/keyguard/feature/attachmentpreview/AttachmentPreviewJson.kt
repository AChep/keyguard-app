package com.artemchep.keyguard.feature.attachmentpreview

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Immutable
class AttachmentPreviewJsonPreview(
    val tree: AttachmentPreviewJsonTree? = null,
    val error: AttachmentPreviewJsonError? = null,
)

enum class AttachmentPreviewJsonError { Invalid, TooLarge, TooDeep, TooManyNodes, DuplicateKeys }
enum class AttachmentPreviewJsonType { Object, Array, String, Number, Boolean, Null }

@Immutable
data class AttachmentPreviewJsonNode(
    val id: String,
    val depth: Int,
    val key: String,
    val parentPath: String,
    val type: AttachmentPreviewJsonType,
    val value: String?,
    val childCount: Int,
    internal val subtreeEnd: Int,
) {
    val expandable: Boolean get() = childCount > 0
}

/** Preorder index: collapsed subtrees can be skipped without visiting their children. */
@Immutable
class AttachmentPreviewJsonTree internal constructor(private val nodes: List<AttachmentPreviewJsonNode>) {
    val rootId: String get() = nodes.first().id

    fun visibleRows(expandedIds: Set<String>): List<AttachmentPreviewJsonNode> = buildList {
        var index = 0
        while (index < nodes.size) {
            val node = nodes[index]
            add(node)
            index = if (node.expandable && node.id !in expandedIds) node.subtreeEnd else index + 1
        }
    }
}

internal const val JSON_TREE_MAX_BYTES = 1024 * 1024
internal const val JSON_TREE_MAX_DEPTH = 64
internal const val JSON_TREE_MAX_NODES = 10_000
private const val VALUE_PREVIEW_CHARS = 512
private const val PATH_PREVIEW_CHARS = 256
private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

/** Replacement decoding is useful for source, but must not repair a malformed JSON document. */
internal fun parseAttachmentPreviewJsonBytes(
    text: String,
    bytes: ByteArray,
    offset: Int,
    checkCancellation: () -> Unit,
): AttachmentPreviewJsonPreview {
    checkCancellation()
    val byteCount = bytes.size - offset
    if (byteCount <= JSON_TREE_MAX_BYTES) {
        try {
            bytes.decodeToString(startIndex = offset, throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return AttachmentPreviewJsonPreview(error = AttachmentPreviewJsonError.Invalid)
        }
    }
    return parseAttachmentPreviewJson(text, byteCount, checkCancellation)
}

/** The original source remains available when a tree cannot faithfully represent it. */
fun parseAttachmentPreviewJson(
    text: String,
    byteCount: Int,
    checkCancellation: () -> Unit = {},
): AttachmentPreviewJsonPreview {
    checkCancellation()
    if (byteCount > JSON_TREE_MAX_BYTES) {
        return AttachmentPreviewJsonPreview(error = AttachmentPreviewJsonError.TooLarge)
    }
    return try {
        val memberCount = inspectJsonStructure(text, checkCancellation)
        val root = Json.parseToJsonElement(text)
        checkCancellation()
        val builder = JsonTreeBuilder(checkCancellation)
        builder.add(root, key = "$", parentPath = "", depth = 0)
        // JsonObject is a map. Duplicate keys discard earlier members; compare
        // against the number of colons outside strings in the original source.
        if (builder.memberCount != memberCount) {
            AttachmentPreviewJsonPreview(error = AttachmentPreviewJsonError.DuplicateKeys)
        } else {
            AttachmentPreviewJsonPreview(tree = AttachmentPreviewJsonTree(builder.nodes.toList()))
        }
    } catch (_: SerializationException) {
        AttachmentPreviewJsonPreview(error = AttachmentPreviewJsonError.Invalid)
    } catch (e: JsonTreeException) {
        AttachmentPreviewJsonPreview(error = e.reason)
    }
}

/** Bound recursion and allocations before invoking the library parser. */
private fun inspectJsonStructure(text: String, checkCancellation: () -> Unit): Int {
    val inspector = JsonStructureInspector()
    text.forEachIndexed { index, char ->
        if (index % JSON_CANCELLATION_INTERVAL == 0) checkCancellation()
        inspector.accept(char)
    }
    return inspector.members
}

private const val JSON_CANCELLATION_INTERVAL = 1024

private class JsonStructureInspector {
    private var quoted = false
    private var escaped = false
    private var depth = 0
    private var tokens = 0
    private var inLiteral = false
    var members = 0
        private set

    fun accept(char: Char) {
        if (quoted) {
            if (char < ' ') throw JsonTreeException(AttachmentPreviewJsonError.Invalid)
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> quoted = false
            }
        } else {
            acceptOutsideString(char)
            // Keys add one token per member. The builder checks the exact count.
            if (tokens > JSON_TREE_MAX_NODES * 2) {
                throw JsonTreeException(AttachmentPreviewJsonError.TooManyNodes)
            }
        }
    }

    private fun acceptOutsideString(char: Char) {
        when (char) {
            '"' -> {
                quoted = true
                tokens++
                inLiteral = false
            }
            '{', '[' -> {
                depth++
                tokens++
                inLiteral = false
                if (depth > JSON_TREE_MAX_DEPTH) throw JsonTreeException(AttachmentPreviewJsonError.TooDeep)
            }
            '}', ']' -> {
                depth--
                inLiteral = false
            }
            ':' -> {
                members++
                inLiteral = false
            }
            ',', ' ', '\r', '\n', '\t' -> inLiteral = false
            else -> if (!inLiteral) {
                tokens++
                inLiteral = true
            }
        }
    }
}

private class JsonTreeBuilder(private val checkCancellation: () -> Unit) {
    val nodes = mutableListOf<AttachmentPreviewJsonNode>()
    var memberCount = 0
        private set

    fun add(element: JsonElement, key: String, parentPath: String, depth: Int) {
        checkCancellation()
        if (nodes.size >= JSON_TREE_MAX_NODES) throw JsonTreeException(AttachmentPreviewJsonError.TooManyNodes)
        val index = nodes.size
        val count = when (element) {
            is JsonObject -> element.size.also { memberCount += it }
            is JsonArray -> element.size
            else -> 0
        }
        val node = AttachmentPreviewJsonNode(
            id = index.toString(),
            depth = depth,
            key = key.ifEmpty { "\"\"" }.previewSummary(VALUE_PREVIEW_CHARS),
            parentPath = parentPath,
            type = element.previewType(),
            value = (element as? JsonPrimitive)?.toString()?.previewSummary(VALUE_PREVIEW_CHARS),
            childCount = count,
            subtreeEnd = index + 1,
        )
        nodes += node
        val path = (if (depth == 0) key else "$parentPath / ${node.key}").previewSummary(PATH_PREVIEW_CHARS)
        when (element) {
            is JsonObject -> element.forEach { (name, value) -> add(value, name, path, depth + 1) }
            is JsonArray -> element.forEachIndexed { childIndex, value -> add(value, "[$childIndex]", path, depth + 1) }
            is JsonPrimitive -> Unit
        }
        nodes[index] = node.copy(subtreeEnd = nodes.size)
    }
}

private fun JsonElement.previewType(): AttachmentPreviewJsonType = when (this) {
    is JsonObject -> AttachmentPreviewJsonType.Object
    is JsonArray -> AttachmentPreviewJsonType.Array
    JsonNull -> AttachmentPreviewJsonType.Null
    is JsonPrimitive -> when {
        isString -> AttachmentPreviewJsonType.String
        content == "true" || content == "false" -> AttachmentPreviewJsonType.Boolean
        JSON_NUMBER.matches(content) -> AttachmentPreviewJsonType.Number
        else -> throw JsonTreeException(AttachmentPreviewJsonError.Invalid)
    }
}

private fun String.previewSummary(limit: Int): String = if (length > limit) take(limit - 1) + "…" else this
private class JsonTreeException(val reason: AttachmentPreviewJsonError) : RuntimeException()
