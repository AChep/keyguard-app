package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.test.testCopyText
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentPreviewJsonTest {
    @Test
    fun `projection preserves order and independently expands sibling containers`() {
        val tree = tree("""{"z":{"value":1},"a":[true,null],"empty":{}}""")
        val roots = tree.visibleRows(setOf(tree.rootId))
        assertEquals(listOf("$", "z", "a", "empty"), roots.map { it.key })
        assertFalse(roots.last().expandable)
        val first = roots[1].id
        val second = roots[2].id
        val expanded = setOf(tree.rootId, first, second)
        val all = tree.visibleRows(expanded)
        assertEquals(listOf("$", "z", "value", "a", "[0]", "[1]", "empty"), all.map { it.key })
        assertEquals(all.size, all.map { it.id }.distinct().size)
        assertEquals(listOf("$", "z", "a", "[0]", "[1]", "empty"), tree.visibleRows(expanded - first).map { it.key })
        assertEquals(all, tree.visibleRows(expanded))
        assertEquals(listOf("$"), tree.visibleRows(emptySet()).map { it.key })
    }

    @Test
    fun `root primitives and empty containers remain visible`() {
        val examples = mapOf(
            "null" to AttachmentPreviewJsonType.Null,
            "false" to AttachmentPreviewJsonType.Boolean,
            "123456789012345678901234567890" to AttachmentPreviewJsonType.Number,
            "-1.2300e+400" to AttachmentPreviewJsonType.Number,
            "\"hello\"" to AttachmentPreviewJsonType.String,
            "[]" to AttachmentPreviewJsonType.Array,
            "{}" to AttachmentPreviewJsonType.Object,
        )
        examples.forEach { (source, type) ->
            val node = tree(source).visibleRows(emptySet()).single()
            assertEquals(type, node.type)
            assertFalse(node.expandable)
            assertEquals(if (source == "[]" || source == "{}") null else source, node.value)
        }
    }

    @Test
    fun `strings do not interfere with structural scanning`() {
        val source = """
            {"":"value","a/b~c":"quotes: \" and brackets: [[]] and slash: \\",
            "Юнікод":"🗝️","nested":{"a/b~c":true}}
        """.trimIndent()
        val tree = tree(source)
        val rows = tree.visibleRows(setOf(tree.rootId))
        assertEquals(listOf("$", "\"\"", "a/b~c", "Юнікод", "nested"), rows.map { it.key })
        assertTrue(rows[2].value!!.contains("brackets"))
    }

    @Test
    fun `duplicate names fall back even when escaped or hiding subtrees`() {
        listOf(
            """{"x":1,"x":2}""",
            """{"x":1,"\u0078":2}""",
            """{"x":{"hidden":1},"x":2}""",
            """{"child":{"x":1,"x":2}}""",
        ).forEach { source -> assertEquals(AttachmentPreviewJsonError.DuplicateKeys, parse(source).error) }
        assertNotNull(parse("""{"one":{"x":1},"two":{"x":2}}""").tree)
    }

    @Test
    fun `malformed and nonstandard JSON stays source only`() {
        listOf("", "{", "[1,]", "{/* comment */}", "{'x':1}", "NaN", "Infinity", "01", "+1", ".1", "1.", "true false")
            .forEach { source ->
                assertEquals(AttachmentPreviewJsonError.Invalid, parse(source).error, source)
            }
    }

    @Test
    fun `raw control characters in keys and values are rejected while escapes remain valid`() {
        for (char in '\u0000'..'\u001f') {
            listOf("{\"key${char}\":1}", "{\"key\":\"value${char}\"}").forEach { source ->
                assertEquals(AttachmentPreviewJsonError.Invalid, parse(source).error, "control ${char.code}")
            }
        }
        assertNotNull(parse("""{"escaped\n\t":"\u0000\u0001\n\t"}""").tree)
    }

    @Test
    fun `malformed UTF8 remains source only without replacing the existing source text`() {
        val prefix = "{\"value\":\"".encodeToByteArray()
        val suffix = "\"}".encodeToByteArray()
        val malformed = listOf(
            byteArrayOf(0xc3.toByte()),
            byteArrayOf(0xc3.toByte(), 0x28),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
        )
        malformed.forEach { value ->
            val bytes = prefix + value + suffix
            val content = assertIs<AttachmentPreviewContent.Json>(decodeTextPreview("data.json", bytes, testCopyText()))
            assertEquals(bytes.decodeToString(), content.text)
            assertNull(content.preview.tree)
            assertEquals(AttachmentPreviewJsonError.Invalid, content.preview.error)
        }
        val valid = "{\"value\":\"�🗝️\"}".encodeToByteArray()
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        for (bytes in listOf(valid, bom + valid)) {
            val content = assertIs<AttachmentPreviewContent.Json>(decodeTextPreview("data.json", bytes, testCopyText()))
            assertNotNull(content.preview.tree)
            assertEquals(valid.decodeToString(), content.text)
        }
    }

    @Test
    fun `oversized malformed UTF8 skips tree decoding and keeps the size fallback`() {
        val bytes = byteArrayOf('"'.code.toByte()) + ByteArray(JSON_TREE_MAX_BYTES) { 'a'.code.toByte() } +
            byteArrayOf(0xff.toByte(), '"'.code.toByte())
        val content = assertIs<AttachmentPreviewContent.Json>(decodeTextPreview("data.json", bytes, testCopyText()))
        assertEquals(bytes.decodeToString(), content.text)
        assertNull(content.preview.tree)
        assertEquals(AttachmentPreviewJsonError.TooLarge, content.preview.error)
    }

    @Test
    fun `depth and byte limits include their boundary`() {
        val atDepth = "[".repeat(JSON_TREE_MAX_DEPTH) + "0" + "]".repeat(JSON_TREE_MAX_DEPTH)
        assertNotNull(parse(atDepth).tree)
        assertEquals(AttachmentPreviewJsonError.TooDeep, parse("[$atDepth]").error)
        val atBytes = "\"" + "a".repeat(JSON_TREE_MAX_BYTES - 2) + "\""
        assertNotNull(parse(atBytes).tree)
        assertEquals(AttachmentPreviewJsonError.TooLarge, parse(" $atBytes").error)
        val multibyte = "\"" + "é".repeat(JSON_TREE_MAX_BYTES / 2) + "\""
        assertEquals(AttachmentPreviewJsonError.TooLarge, parse(multibyte).error)
    }

    @Test
    fun `node count includes the root and guards wide arrays`() {
        val atLimit = List(JSON_TREE_MAX_NODES - 1) { "0" }.joinToString(",", "[", "]")
        assertNotNull(parse(atLimit).tree)
        assertEquals(AttachmentPreviewJsonError.TooManyNodes, parse(atLimit.dropLast(1) + ",0]").error)
        val huge = List(JSON_TREE_MAX_NODES * 3) { "null" }.joinToString(",", "[", "]")
        assertEquals(AttachmentPreviewJsonError.TooManyNodes, parse(huge).error)
    }

    @Test
    fun `long display values and ancestor context are bounded without changing source`() {
        val long = "a".repeat(2048)
        val tree = tree("""{"$long":{"child":"$long"}}""")
        val rows = tree.visibleRows(setOf("0", "1"))
        assertEquals(512, rows[1].key.length)
        assertEquals(512, rows[2].value!!.length)
        assertTrue(rows[2].parentPath.length <= 256)
        assertTrue(rows[2].value!!.endsWith("…"))
    }

    @Test
    fun `cancellation propagates during both scanning and building`() {
        val source = List(100) { "true" }.joinToString(",", "[", "]")
        for (cancelAt in listOf(1, 2, 10)) {
            var checks = 0
            assertFailsWith<CancellationException> {
                parseAttachmentPreviewJson(source, source.length) {
                    if (++checks == cancelAt) throw CancellationException()
                }
            }
        }
    }

    @Test
    fun `JSON and JSON LD copy exact source including whitespace when tree unavailable`() {
        val source = "  {\n \"a\": 1, \"a\": 2\n}\n"
        var copied: String? = null
        val clipboard = object : ClipboardService {
            override fun setPrimaryClip(value: String, concealed: Boolean) {
                copied = value
                assertTrue(concealed)
            }
            override fun clearPrimaryClip() = Unit
            override fun hasCopyNotification() = true
        }
        for (name in listOf("data.json", "DATA.JSONLD")) {
            val content = assertIs<AttachmentPreviewContent.Json>(
                decodeTextPreview(name, source.encodeToByteArray(), testCopyText(clipboard)),
            )
            assertEquals(source, content.code.text)
            assertNull(content.preview.tree)
            assertEquals(AttachmentPreviewJsonError.DuplicateKeys, content.preview.error)
            content.onCopy()
            assertEquals(source, copied)
        }
        listOf("data.jsonc", "data.json5", "data.txt").forEach { name ->
            assertIs<AttachmentPreviewContent.Text>(decodeTextPreview(name, "{}".encodeToByteArray(), testCopyText()))
        }
    }

    private fun parse(source: String) = parseAttachmentPreviewJson(source, source.encodeToByteArray().size)
    private fun tree(source: String) = assertNotNull(parse(source).tree)
}
