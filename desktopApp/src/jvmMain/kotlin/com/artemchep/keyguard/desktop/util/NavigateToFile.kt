package com.artemchep.keyguard.desktop.util

import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

fun navigateToFile(
    uri: String,
) {
    val file = Paths.get(URI.create(uri)).toFile()
    Desktop.getDesktop().open(file)
}
