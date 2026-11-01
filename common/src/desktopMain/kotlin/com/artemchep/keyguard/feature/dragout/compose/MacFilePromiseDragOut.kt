package com.artemchep.keyguard.feature.dragout.compose

import com.artemchep.desktop.MacFilePromiseCallbacks
import com.artemchep.desktop.MacFilePromiseReader
import com.artemchep.desktop.NativeDragSession
import com.artemchep.desktop.startMacFilePromises

/**
 * AppKit writes each promised file straight into the recipient's destination. With
 * [NativeDragFiles.Promised] the drag starts on names alone, and each write waits for its file.
 *
 * An accepted drop retires once every file has had one successful write. A repeat write then
 * fails, and the receiver reports it as an error. The store's deadline still retires a drop
 * whose destination never asks for its files.
 */
internal class MacFilePromiseDragOut(
    files: NativeDragFiles,
    private val windowHandle: Long,
    private val startPromises: (Long, List<String>, MacFilePromiseCallbacks) -> NativeDragSession? =
        ::startMacFilePromises,
) : NativeDragOffer(files), MacFilePromiseCallbacks {
    override fun startNative(): NativeDragSession? = startPromises(windowHandle, names, this)

    override fun openReader(index: Int): MacFilePromiseReader {
        val content = offered(index)
        val reader = content.openReader()
        return object : MacFilePromiseReader {
            override val size: Long = content.size

            override fun readAt(offset: Long, data: ByteArray, length: Int): Int =
                reader.readAt(offset, data, 0, length)

            override fun close() = reader.close()
        }
    }

    override fun written(index: Int) = delivered(index)
}
