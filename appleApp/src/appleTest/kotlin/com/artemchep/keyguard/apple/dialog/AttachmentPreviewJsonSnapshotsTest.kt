package com.artemchep.keyguard.apple.dialog

import com.artemchep.keyguard.feature.attachmentpreview.parseAttachmentPreviewJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AttachmentPreviewJsonSnapshotsTest {
    @Test
    fun invalidDocumentsKeepIdentityForHighlightingButResetForAnotherDocument() {
        val source = "{"
        val first = parseAttachmentPreviewJson(source, source.encodeToByteArray().size)
        val second = parseAttachmentPreviewJson(source, source.encodeToByteArray().size)
        val identity = first.documentId(previousPreview = null, previousId = null)

        assertEquals(identity, first.documentId(previousPreview = first, previousId = identity))
        assertNotEquals(identity, second.documentId(previousPreview = first, previousId = identity))
        assertNotEquals(identity, first.documentId(previousPreview = second, previousId = identity))
    }

    @Test
    fun highlightUpdatesKeepTheSameNativeTreeIdentity() {
        val source = """{"nested":{"value":true}}"""
        val tree = assertNotNull(parseAttachmentPreviewJson(source, source.encodeToByteArray().size).tree)
        val snapshot = tree.toSnapshot(previous = null)

        assertSame(snapshot, tree.toSnapshot(previous = snapshot))

        // Another attachment can have identical bytes, but must start with its own expansion state.
        val otherTree = assertNotNull(parseAttachmentPreviewJson(source, source.encodeToByteArray().size).tree)
        assertNotSame(snapshot, otherTree.toSnapshot(previous = snapshot))
    }

    @Test
    fun nativeRowsPreserveSharedTypesValuesAndExpansion() {
        val source = """{"account":{"name":"A"},"flags":[true,null,12.50],"empty":{},"emptyArray":[]}"""
        val tree = assertNotNull(parseAttachmentPreviewJson(source, source.encodeToByteArray().size).tree)
        val snapshot = tree.toSnapshot(previous = null)
        val rootRows = snapshot.visibleRows(setOf(snapshot.rootId))
        assertEquals(listOf("$", "account", "flags", "empty", "emptyArray"), rootRows.map { it.key })
        assertEquals(4, rootRows.first().childCount)
        assertTrue(rootRows.first().expandable)
        assertFalse(rootRows.first { it.key == "empty" }.expandable)
        assertFalse(rootRows.first { it.key == "emptyArray" }.expandable)

        val expanded = setOf(snapshot.rootId, rootRows[1].id, rootRows[2].id)
        val rows = snapshot.visibleRows(expanded)
        assertEquals(tree.visibleRows(expanded).map { it.id }, rows.map { it.id })
        assertEquals(
            listOf("$", "account", "name", "flags", "[0]", "[1]", "[2]", "empty", "emptyArray"),
            rows.map { it.key },
        )
        assertEquals(
            listOf(
                AttachmentPreviewJsonTypeSnapshot.OBJECT,
                AttachmentPreviewJsonTypeSnapshot.OBJECT,
                AttachmentPreviewJsonTypeSnapshot.STRING,
                AttachmentPreviewJsonTypeSnapshot.ARRAY,
                AttachmentPreviewJsonTypeSnapshot.BOOLEAN,
                AttachmentPreviewJsonTypeSnapshot.NULL,
                AttachmentPreviewJsonTypeSnapshot.NUMBER,
                AttachmentPreviewJsonTypeSnapshot.OBJECT,
                AttachmentPreviewJsonTypeSnapshot.ARRAY,
            ),
            rows.map { it.type },
        )
        assertEquals(listOf("\"A\"", "true", "null", "12.50"), rows.mapNotNull { it.value })
        assertEquals(2, rows.first { it.key == "name" }.depth)
        assertEquals(tree.visibleRows(expanded).map { it.parentPath }, rows.map { it.parentPath })
        assertEquals(rootRows, snapshot.visibleRows(setOf(snapshot.rootId)))
    }
}
