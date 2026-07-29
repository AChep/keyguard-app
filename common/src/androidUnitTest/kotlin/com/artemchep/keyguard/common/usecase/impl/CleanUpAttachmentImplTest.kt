package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.util.io.artifact.TemporaryArtifactRole
import com.artemchep.keyguard.util.io.artifact.temporaryArtifactName
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleanUpAttachmentImplTest {
    @Test
    fun `keeps a referenced download`() = withTemporaryDirectory { root ->
        val file = root.resolve("download.bin").apply {
            writeText("download")
        }

        val shouldDelete = shouldDeleteAttachmentFile(
            file = file.toFile(),
            possibleFiles = setOf(file.toFile()),
        )

        assertFalse(shouldDelete)
    }

    @Test
    fun `deletes an unreferenced download immediately`() = withTemporaryDirectory { root ->
        val file = root.resolve("orphan.bin").apply {
            writeText("orphan")
        }

        val shouldDelete = shouldDeleteAttachmentFile(
            file = file.toFile(),
            possibleFiles = emptySet(),
        )

        assertTrue(shouldDelete)
    }

    @Test
    fun `keeps a canonical staging temporary for the native sweeper`() =
        withTemporaryDirectory { root ->
            val file = root.resolve(
                temporaryArtifactName(
                    TemporaryArtifactRole.New,
                    "123e4567-e89b-42d3-a456-426614174000",
                ),
            ).apply {
                writeText("staging")
            }

            val shouldDelete = shouldDeleteAttachmentFile(
                file = file.toFile(),
                possibleFiles = emptySet(),
            )

            assertFalse(shouldDelete)
        }

    @Test
    fun `keeps malformed and future reserved names for compatible readers`() =
        withTemporaryDirectory { root ->
            for (name in listOf(".kg-tmp-malformed", ".kg-tmp-v99-n-future.tmp")) {
                val file = root.resolve(name).apply {
                    writeText("reserved")
                }

                val shouldDelete = shouldDeleteAttachmentFile(
                    file = file.toFile(),
                    possibleFiles = emptySet(),
                )

                assertFalse(shouldDelete, name)
            }
        }
}

private inline fun withTemporaryDirectory(
    block: (java.nio.file.Path) -> Unit,
) {
    val root = createTempDirectory("attachment-cleanup")
    try {
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}
