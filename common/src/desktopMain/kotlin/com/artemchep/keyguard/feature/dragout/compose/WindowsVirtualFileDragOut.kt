package com.artemchep.keyguard.feature.dragout.compose

import com.artemchep.desktop.NativeDragSession
import com.artemchep.desktop.WindowsVirtualFileCallbacks
import com.artemchep.desktop.WindowsVirtualFileEntry
import com.artemchep.desktop.WindowsVirtualFileReader
import com.artemchep.desktop.startWindowsVirtualFiles
import com.artemchep.keyguard.common.service.dragout.PreparedContent

/** OLE recipients read each virtual file through positional streams, possibly after the drag loop returns. */
internal class WindowsVirtualFileDragOut(
    contents: List<PreparedContent>,
    private val startVirtualFiles:
        (List<WindowsVirtualFileEntry>, WindowsVirtualFileCallbacks) -> NativeDragSession? =
        ::startWindowsVirtualFiles,
) : NativeDragOffer(NativeDragFiles.Prepared(contents)), WindowsVirtualFileCallbacks {
    override fun startNative(): NativeDragSession? {
        val files = contents.mapIndexed { index, content -> WindowsVirtualFileEntry(names[index], content.size) }
        return startVirtualFiles(files, this)
    }

    override fun openReader(index: Int): WindowsVirtualFileReader {
        val reader = offered(index).openReader()
        return object : WindowsVirtualFileReader {
            override fun readAt(offset: Long, data: ByteArray, length: Int): Int =
                reader.readAt(offset, data, 0, length)

            override fun close() = reader.close()
        }
    }
}
