package com.artemchep.keyguard.util.io

import kotlinx.io.files.FileNotFoundException

actual fun LocalPath.readTextIfExists(): String? = try {
    readText()
} catch (_: FileNotFoundException) {
    null
}
