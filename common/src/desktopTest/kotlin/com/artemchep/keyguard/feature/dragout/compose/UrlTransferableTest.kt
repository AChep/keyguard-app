package com.artemchep.keyguard.feature.dragout.compose

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlTransferableTest {
    @Test
    fun `one link is offered as text, a URI list and a Java URL`() {
        val transferable = UrlTransferable(listOf("https://example.com/login"))
        assertEquals("https://example.com/login", transferable.getTransferData(DataFlavor.stringFlavor))
        assertEquals("https://example.com/login\r\n", transferable.getTransferData(UrlTransferable.URI_LIST))
        assertEquals(URI("https://example.com/login").toURL(), transferable.getTransferData(UrlTransferable.URL_FLAVOR))
        assertFalse(transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        assertFailsWith<UnsupportedFlavorException> { transferable.getTransferData(DataFlavor.javaFileListFlavor) }
    }

    @Test
    fun `several links have no single Java URL`() {
        val transferable = UrlTransferable(listOf("https://a.example/", "https://b.example/"))
        assertEquals("https://a.example/\nhttps://b.example/", transferable.getTransferData(DataFlavor.stringFlavor))
        assertEquals(
            "https://a.example/\r\nhttps://b.example/\r\n",
            transferable.getTransferData(UrlTransferable.URI_LIST),
        )
        assertTrue(transferable.isDataFlavorSupported(UrlTransferable.URI_LIST))
        assertFalse(transferable.isDataFlavorSupported(UrlTransferable.URL_FLAVOR))
    }
}
