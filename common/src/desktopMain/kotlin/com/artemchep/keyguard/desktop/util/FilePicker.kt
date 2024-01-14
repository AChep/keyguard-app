package com.artemchep.keyguard.desktop.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.wunderbox.nfd.FileDialog
import tv.wunderbox.nfd.FileDialogResult
import java.awt.Component
import java.io.File

fun showFilePicker(
    composeWindow: Component,
    filters: List<FileDialog.Filter> = emptyList(),
    block: (File) -> Unit,
) {
    GlobalScope.launch(Dispatchers.Default) {
        val outFileResult = FileDialog
            .default(composeWindow)
            .pickFile(
                filters = filters,
            )
        if (outFileResult is FileDialogResult.Success) {
            val outFile = outFileResult.value
            withContext(Dispatchers.Main) {
                block(outFile)
            }
        }
    }
}
