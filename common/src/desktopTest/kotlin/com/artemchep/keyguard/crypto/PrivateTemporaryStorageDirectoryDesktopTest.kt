package com.artemchep.keyguard.crypto

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals

class PrivateTemporaryStorageDirectoryDesktopTest {
    @Test
    fun usesAKeyguardOwnedChildInsteadOfTheSharedSystemTemporaryRoot() {
        val systemTemporaryRoot = Path.of(System.getProperty("java.io.tmpdir"))
            .toAbsolutePath()
            .normalize()
        val privateRoot = Path.of(privateTemporaryStorageDirectory().value)
            .toAbsolutePath()
            .normalize()

        assertEquals(systemTemporaryRoot, privateRoot.parent)
        assertEquals("keyguard-private", privateRoot.fileName.toString())
        if ("posix" in privateRoot.fileSystem.supportedFileAttributeViews()) {
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(privateRoot),
            )
        }
    }
}
