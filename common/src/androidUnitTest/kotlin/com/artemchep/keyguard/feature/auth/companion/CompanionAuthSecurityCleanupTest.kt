package com.artemchep.keyguard.feature.auth.companion

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionAuthSecurityCleanupTest {
    @Test
    fun `inactive request directory with secrets is fully removed`() {
        withCompanionAuthRoot { rootDir ->
            val requestDir = requestDir(rootDir)
            requestDir.resolve("database.kdbx").writeText("db")
            requestDir.resolve("database.key").writeText("key")
            requestDir.resolve("session.json").writeText("legacy")

            sweepCompanionAuthRequestDirs(
                rootDir = rootDir,
            )

            assertFalse(requestDir.exists())
        }
    }

    @Test
    fun `directory without session file is removed as orphaned temp data`() {
        withCompanionAuthRoot { rootDir ->
            val requestDir = requestDir(rootDir)
            requestDir.resolve("database.kdbx").writeText("db")

            sweepCompanionAuthRequestDirs(
                rootDir = rootDir,
            )

            assertFalse(requestDir.exists())
        }
    }

    @Test
    fun `active in memory request directory without session file is preserved`() {
        withCompanionAuthRoot { rootDir ->
            val requestDir = requestDir(rootDir)
            requestDir.resolve("database.kdbx").writeText("db")

            sweepCompanionAuthRequestDirs(
                rootDir = rootDir,
                activeRequestIds = setOf(REQUEST_ID),
            )

            assertTrue(requestDir.exists())
            assertTrue(requestDir.resolve("database.kdbx").exists())
        }
    }

    @Test
    fun `legacy session file does not keep inactive request directory alive`() {
        withCompanionAuthRoot { rootDir ->
            val requestDir = requestDir(rootDir)
            requestDir.resolve("database.kdbx").writeText("db")
            requestDir.resolve("session.json").writeText(
                """
                {
                  "requestId": "$REQUEST_ID",
                  "provider": "KEEPASS",
                  "role": "Initiator",
                  "createdAtEpochMillis": 1000000
                }
                """.trimIndent(),
            )

            sweepCompanionAuthRequestDirs(
                rootDir = rootDir,
            )

            assertFalse(requestDir.exists())
        }
    }

    @Test
    fun `active in memory request directory with legacy session file is preserved`() {
        withCompanionAuthRoot { rootDir ->
            val requestDir = requestDir(rootDir)
            requestDir.resolve("database.kdbx").writeText("db")
            requestDir.resolve("session.json").writeText("legacy")

            sweepCompanionAuthRequestDirs(
                rootDir = rootDir,
                activeRequestIds = setOf(REQUEST_ID),
            )

            assertTrue(requestDir.exists())
            assertTrue(requestDir.resolve("database.kdbx").exists())
            assertTrue(requestDir.resolve("session.json").exists())
        }
    }

    @Test
    fun `directory with non canonical request id name is removed`() {
        withCompanionAuthRoot { rootDir ->
            val requestDir = rootDir.resolve("not-a-uuid").apply {
                mkdirs()
            }
            requestDir.resolve("session.json").writeText("ignored")

            sweepCompanionAuthRequestDirs(
                rootDir = rootDir,
            )

            assertFalse(requestDir.exists())
        }
    }

    @Test
    fun `non directory artifact is removed`() {
        withCompanionAuthRoot { rootDir ->
            val artifact = rootDir.resolve("session.json")
            artifact.writeText("legacy")

            sweepCompanionAuthRequestDirs(
                rootDir = rootDir,
            )

            assertFalse(artifact.exists())
        }
    }
}

private fun withCompanionAuthRoot(
    block: (File) -> Unit,
) {
    val rootDir = Files.createTempDirectory("companion-auth-cleanup").toFile()
    try {
        block(rootDir)
    } finally {
        rootDir.deleteRecursively()
    }
}

private fun requestDir(
    rootDir: File,
    requestId: String = REQUEST_ID,
): File = rootDir.resolve(requestId).apply {
    mkdirs()
}

private const val REQUEST_ID = "123e4567-e89b-12d3-a456-426614174000"
