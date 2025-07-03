package com.artemchep.keyguard.desktop.util

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun showFilePicker(
    type: FileKitType = FileKitType.File(),
    block: (File) -> Unit,
) {
    GlobalScope.launch(Dispatchers.Default) {
        runCatching {
            FileKit.openFilePicker(type = type)
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
