package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.io.toLocalPath
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private const val KEYGUARD_PRIVATE_TEMPORARY_DIRECTORY = "keyguard-private"

internal actual fun privateTemporaryStorageDirectory(): LocalPath {
    val directory = Path.of(System.getProperty("java.io.tmpdir"))
        .toAbsolutePath()
        .resolve(KEYGUARD_PRIVATE_TEMPORARY_DIRECTORY)
    try {
        if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
            Files.createDirectory(
                directory,
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------"),
                ),
            )
        } else {
            Files.createDirectory(directory)
        }
    } catch (_: FileAlreadyExistsException) {
        check(Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            "Private temporary storage root is not a real directory"
        }
    }
    if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
        Files.setPosixFilePermissions(
            directory,
            PosixFilePermissions.fromString("rwx------"),
        )
    }
    return directory.toLocalPath()
}
