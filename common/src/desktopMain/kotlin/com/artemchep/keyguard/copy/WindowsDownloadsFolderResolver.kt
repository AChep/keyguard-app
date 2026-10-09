package com.artemchep.keyguard.copy

import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.ShlObj
import com.sun.jna.platform.win32.W32Errors
import com.sun.jna.ptr.PointerByReference
import net.harawata.appdirs.AppDirsException
import net.harawata.appdirs.impl.WindowsAppDirs.FolderId
import net.harawata.appdirs.impl.WindowsFolderResolver

/**
 * Preserves the configured Downloads path even when it is missing or inaccessible.
 * Directory creation and mount checks belong to [atomicAppDirectory].
 */
internal class WindowsDownloadsFolderResolver(
    private val delegate: WindowsFolderResolver,
    private val resolveDownloads: () -> String = ::windowsDownloadsDirectory,
) : WindowsFolderResolver {
    override fun resolveFolder(folderId: FolderId): String = when (folderId) {
        FolderId.DOWNLOADS -> resolveDownloads()
        else -> delegate.resolveFolder(folderId)
    }
}

private fun windowsDownloadsDirectory(): String {
    val path = PointerByReference()
    try {
        val result = Shell32.INSTANCE.SHGetKnownFolderPath(
            KnownFolders.FOLDERID_Downloads,
            ShlObj.KNOWN_FOLDER_FLAG.DONT_VERIFY.flag,
            null,
            path,
        )
        if (!W32Errors.SUCCEEDED(result.toInt())) {
            throw AppDirsException("SHGetKnownFolderPath returns an error: $result")
        }
        return path.value?.getWideString(0)
            ?: throw AppDirsException("SHGetKnownFolderPath returned no Downloads path")
    } finally {
        // Windows requires this allocation to be freed on failure as well as success.
        path.value?.let { Ole32.INSTANCE.CoTaskMemFree(it) }
    }
}
