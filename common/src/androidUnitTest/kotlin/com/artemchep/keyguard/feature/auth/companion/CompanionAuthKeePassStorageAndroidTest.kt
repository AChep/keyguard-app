package com.artemchep.keyguard.feature.auth.companion

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionAuthKeePassStorageAndroidTest {
    @Test
    fun `staged keepass database is copied outside companion auth request dir`() {
        withTempDir { filesDir ->
            val requestId = "123e4567-e89b-12d3-a456-426614174000"
            val requestDir = filesDir
                .resolve("companion-auth")
                .resolve(requestId)
                .apply { mkdirs() }
            val sourceFile = requestDir.resolve("database.kdbx").apply {
                writeText("db-data")
            }

            val stagedFile = stageManagedCompanionKeePassDatabase(
                filesDir = filesDir,
                requestId = requestId,
                sourceFile = sourceFile,
            )

            assertTrue(stagedFile.exists())
            assertEquals("db-data", stagedFile.readText())
            assertTrue(stagedFile.toPath().startsWith(companionKeePassRootDir(filesDir).toPath()))
            assertFalse(stagedFile.toPath().startsWith(requestDir.toPath()))
        }
    }

    @Test
    fun `deleting transient request artifacts leaves staged database intact`() {
        withTempDir { filesDir ->
            val requestId = "123e4567-e89b-12d3-a456-426614174000"
            val requestDir = filesDir
                .resolve("companion-auth")
                .resolve(requestId)
                .apply { mkdirs() }
            val sourceFile = requestDir.resolve("database.kdbx").apply {
                writeText("db-data")
            }

            val stagedFile = stageManagedCompanionKeePassDatabase(
                filesDir = filesDir,
                requestId = requestId,
                sourceFile = sourceFile,
            )

            requestDir.deleteRecursively()

            assertFalse(requestDir.exists())
            assertTrue(stagedFile.exists())
            assertEquals("db-data", stagedFile.readText())
        }
    }

    @Test
    fun `failed import cleanup removes staged keepass database`() {
        withTempDir { filesDir ->
            val requestId = "123e4567-e89b-12d3-a456-426614174000"
            val requestDir = filesDir
                .resolve("companion-auth")
                .resolve(requestId)
                .apply { mkdirs() }
            val sourceFile = requestDir.resolve("database.kdbx").apply {
                writeText("db-data")
            }

            val stagedFile = stageManagedCompanionKeePassDatabase(
                filesDir = filesDir,
                requestId = requestId,
                sourceFile = sourceFile,
            )
            assertTrue(stagedFile.exists())

            deleteManagedCompanionKeePassArtifacts(
                filesDir = filesDir,
                requestId = requestId,
            )

            assertFalse(stagedFile.exists())
            assertFalse(companionKeePassRequestDir(filesDir, requestId).exists())
        }
    }
}

private fun withTempDir(
    block: (java.io.File) -> Unit,
) {
    val dir = Files.createTempDirectory("companion-keepass-storage").toFile()
    try {
        block(dir)
    } finally {
        dir.deleteRecursively()
    }
}
