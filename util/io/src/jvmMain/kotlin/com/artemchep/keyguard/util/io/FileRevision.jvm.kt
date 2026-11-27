package com.artemchep.keyguard.util.io

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

actual fun LocalPath.fileRevisionOrNull(): FileRevision? = try {
    val attributes = Files.readAttributes(Paths.get(value), BasicFileAttributes::class.java)
    FileRevision(
        modifiedNanos = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
        size = attributes.size(),
        // The device and inode on Unix; null on Windows.
        identity = attributes.fileKey()?.toString(),
    )
} catch (_: NoSuchFileException) {
    null
}
