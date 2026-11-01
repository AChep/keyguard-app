package com.artemchep.keyguard.feature.dragout.compose

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.net.URI

/** Links as plain text, a URI list and, for one link, a Java URL that AWT maps to each OS's URL format. */
internal class UrlTransferable(
    private val urls: List<String>,
) : Transferable {
    init {
        require(urls.isNotEmpty()) { "A link drag must offer a link" }
    }

    private val url = urls.singleOrNull()?.let { runCatching { URI(it).toURL() }.getOrNull() }

    override fun getTransferDataFlavors(): Array<DataFlavor> = buildList {
        add(DataFlavor.stringFlavor)
        add(URI_LIST)
        if (url != null) add(URL_FLAVOR)
    }.toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in transferDataFlavors

    override fun getTransferData(flavor: DataFlavor): Any = when {
        flavor == DataFlavor.stringFlavor -> urls.joinToString("\n")
        flavor == URI_LIST -> urls.joinToString(separator = "\r\n", postfix = "\r\n")
        flavor == URL_FLAVOR && url != null -> url
        else -> throw UnsupportedFlavorException(flavor)
    }

    companion object {
        val URI_LIST = DataFlavor("text/uri-list;class=java.lang.String")
        val URL_FLAVOR = DataFlavor("application/x-java-url;class=java.net.URL")

        init {
            // X11 maps `text/uri-list` to file lists only; offer links under the same native type.
            (SystemFlavorMap.getDefaultFlavorMap() as? SystemFlavorMap)
                ?.addUnencodedNativeForFlavor(URI_LIST, "text/uri-list")
        }
    }
}
