package com.artemchep.keyguard.desktop.util

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun showFilePicker(
    type: FileKitType = FileKitType.File(),
    block: (File) -> Unit,
) = showFileBlock(
    block = block,
) {
    FileKit.openFilePicker(type = type)
}

fun showFileSaver(
    suggestedName: String,
    extension: String?,
    block: (File) -> Unit,
) = showFileBlock(
    block = block,
) {
    FileKit.openFileSaver(
        suggestedName = suggestedName,
        extension = extension,
    )
}

private inline fun showFileBlock(
    noinline block: (File) -> Unit,
    crossinline show: suspend () -> PlatformFile?,
) {
    GlobalScope.launch(Dispatchers.Default) {
        runCatching {
            show()
        }.onSuccess { file ->
            val f = file?.file
                ?: return@onSuccess
            withContext(Dispatchers.Main) {
                block(f)
            }
        }.onFailure { e ->
            e.printStackTrace()
        }
    }
}
