package com.artemchep.keyguard.util.io

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths

actual fun LocalPath.readTextIfExists(): String? = try {
    // FileInputStream conflates missing files and permission errors.
    Files.newInputStream(Paths.get(value)).bufferedReader(Charsets.UTF_8).use { it.readText() }
} catch (_: NoSuchFileException) {
    null
}
