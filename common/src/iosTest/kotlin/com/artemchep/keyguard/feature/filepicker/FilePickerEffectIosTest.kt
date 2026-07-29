package com.artemchep.keyguard.feature.filepicker

import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.io.artifact.isReservedTemporaryArtifactName
import com.artemchep.keyguard.util.io.resolve
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FilePickerEffectIosTest {
    @Test
    fun exportFileNameSanitizesNonPortableSpellings() {
        assertEquals("leaf.txt", "folder/leaf.txt".sanitizedExportFileName())
        assertEquals("leaf.txt", "folder\\leaf.txt".sanitizedExportFileName())
        assertEquals("report_name.txt", "report:name.txt".sanitizedExportFileName())
        assertEquals("report_name.txt", "report\u0000name.txt".sanitizedExportFileName())
        assertEquals("export", ".".sanitizedExportFileName())
        assertEquals("export", "..".sanitizedExportFileName())
        assertEquals("export", " ".sanitizedExportFileName())
        assertEquals("дані_日本語.txt", "дані_日本語.txt".sanitizedExportFileName())
    }

    @Test
    fun stagingDirectoryUsesASeparateNonReservedNamespace() {
        val name = newFilePickerStagingDirectoryName().value

        assertTrue(name.startsWith(FILE_PICKER_STAGING_PREFIX))
        assertTrue(isFilePickerStagingDirectoryName(name))
        assertFalse(isReservedTemporaryArtifactName(name))
    }

    @Test
    fun stagingDirectoryNameRequiresCanonicalUuidSuffix() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"

        assertTrue(isFilePickerStagingDirectoryName("$FILE_PICKER_STAGING_PREFIX$uuid"))
        assertFalse(isFilePickerStagingDirectoryName(FILE_PICKER_STAGING_PREFIX))
        assertFalse(isFilePickerStagingDirectoryName("${FILE_PICKER_STAGING_PREFIX}not-a-uuid"))
        assertFalse(isFilePickerStagingDirectoryName("$FILE_PICKER_STAGING_PREFIX$uuid-extra"))
        assertFalse(isFilePickerStagingDirectoryName("$FILE_PICKER_STAGING_PREFIX${uuid.uppercase()}"))
        assertFalse(isFilePickerStagingDirectoryName("other-$uuid"))
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun previousProcessCleanupRemovesOnlyMatchingRealDirectories() {
        val fileManager = NSFileManager.defaultManager
        val root = LocalPath(NSTemporaryDirectory())
            .resolve("keyguard-file-picker-cleanup-test-${Uuid.random()}")
        val matching = root.resolve(newFilePickerStagingDirectoryName().value)
        val unrelated = root.resolve("unrelated")
        val malformed = root.resolve("${FILE_PICKER_STAGING_PREFIX}not-a-uuid")
        val symlink = root.resolve(newFilePickerStagingDirectoryName().value)

        try {
            listOf(root, matching, unrelated, malformed).forEach { directory ->
                assertTrue(
                    fileManager.createDirectoryAtPath(
                        path = directory.value,
                        withIntermediateDirectories = true,
                        attributes = null,
                        error = null,
                    ),
                )
            }
            assertTrue(
                fileManager.createFileAtPath(
                    path = matching.resolve("export.txt").value,
                    contents = null,
                    attributes = null,
                ),
            )
            assertTrue(
                fileManager.createSymbolicLinkAtPath(
                    path = symlink.value,
                    withDestinationPath = unrelated.value,
                    error = null,
                ),
            )

            cleanUpFilePickerStagingDirectories(root)

            assertFalse(fileManager.fileExistsAtPath(matching.value))
            assertTrue(fileManager.fileExistsAtPath(unrelated.value))
            assertTrue(fileManager.fileExistsAtPath(malformed.value))
            assertTrue(fileManager.fileExistsAtPath(symlink.value))
        } finally {
            fileManager.removeItemAtPath(
                path = root.value,
                error = null,
            )
        }
    }
}
