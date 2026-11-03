package com.artemchep.keyguard.feature.dragout.compose

import com.artemchep.desktop.NativeDragSession
import com.artemchep.desktop.WindowsVirtualFileCallbacks
import com.artemchep.desktop.WindowsVirtualFileEntry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsVirtualFileDragOutTest {
    @Test
    fun positionalReadersServeEachFileUntilTheStoreRevokesTheOffer() {
        val first = DragOutTestContent("a.txt", byteArrayOf(1, 2, 3, 4))
        val second = DragOutTestContent("b.txt", byteArrayOf(9, 8))
        var callbacks: WindowsVirtualFileCallbacks? = null
        var files: List<WindowsVirtualFileEntry> = emptyList()
        var revokes = 0
        val offer = WindowsVirtualFileDragOut(listOf(first.content, second.content)) { value, native ->
            files = value
            callbacks = native
            NativeDragSession { revokes++ }
        }
        assertTrue(offer.start {})
        assertEquals(listOf("a.txt", "b.txt"), files.map { it.name })
        callbacks!!.ended(true)

        val reader = callbacks!!.openReader(0)
        val data = ByteArray(8)
        assertEquals(2, reader.readAt(2, data, 2))
        assertContentEquals(byteArrayOf(3, 4), data.copyOf(2))
        assertEquals(0, reader.readAt(4, data, 8))
        callbacks!!.openReader(1).use { assertEquals(2, it.readAt(0, data, 8)) }

        first.retire()
        second.retire()
        assertEquals(1, revokes)
        assertFailsWith<IllegalStateException> { callbacks!!.openReader(1) }
        assertEquals(1, reader.readAt(3, data, 1))
        reader.close()
        callbacks!!.retired()
        assertEquals(listOf(1, 1), listOf(first.closes, second.closes))
        assertEquals(2, first.readersClosed + second.readersClosed)
    }
}
