package com.artemchep.keyguard.test.io

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.FileSystemOperationException
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.artifact.isReservedTemporaryArtifactName
import com.artemchep.keyguard.util.io.atomic.AchievedSyncLevel
import com.artemchep.keyguard.util.io.atomic.AtomicDirectory
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryPermissions
import com.artemchep.keyguard.util.io.atomic.AtomicFilePermissions
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationPolicy
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationState
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.AtomicWriteReceipt
import com.artemchep.keyguard.util.io.atomic.ExistingParentLinkPolicy
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.atomic.openAtomicDirectory
import kotlinx.io.write
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@OptIn(InternalKeyguardIoApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
class NativeIoAtomicDirectorySmokeTest {
    @Test
    fun retainedDirectoryPublishesDurableOwnerOnlyBytesWithoutReservedArtifacts() {
        withSmokeRoot { root, directory, relativeParent ->
            val payload = "native-io-android-smoke".encodeToByteArray()
            val relative = "$relativeParent/payload.bin"

            val receipt = directory.write(relative, payload)

            val destination = File(root, relative)
            assertArrayEquals(payload, destination.readBytes())
            assertEquals(AchievedSyncLevel.FileSynchronized, receipt.achievedSyncLevel)
            assertEquals(AtomicPublicationState.Published, receipt.publicationState)
            assertNull(receipt.cleanupFailure)
            assertOwnerOnlyFile(destination)
            assertOwnerOnlyDirectory(destination.parentFile!!)
            assertNoReservedArtifacts(destination.parentFile!!)
        }
    }

    @Test
    fun malformedLoneSurrogateIsRejectedBeforeDestinationFilesystemAccess() {
        withSmokeRoot { root, directory, relativeParent ->
            val relative = "$relativeParent/malformed-\uD800.bin"

            val failure = assertThrows(FileSystemOperationException::class.java) {
                directory.openAtomicFileTransaction(
                    relativeDestination = AtomicRelativePath.parse(relative),
                    options = OPTIONS,
                ).use { }
            }

            assertEquals(FileSystemFailureKind.InvalidInput, failure.failure.kind)
            val destinationParent = File(root, relativeParent)
            assertFalse(destinationParent.exists())
            assertNoReservedArtifacts(destinationParent)
        }
    }

    @Test
    fun replacementCharacterFilenameIsPreserved() {
        assertUnicodeFilenameRoundTrips("replacement-\uFFFD.bin")
    }

    @Test
    fun astralFilenameRoundTrips() {
        assertUnicodeFilenameRoundTrips("astral-\uD83D\uDE80.bin")
    }

    private fun assertUnicodeFilenameRoundTrips(fileName: String) {
        withSmokeRoot { root, directory, relativeParent ->
            val payload = fileName.encodeToByteArray()
            val relative = "$relativeParent/$fileName"

            directory.write(relative, payload)

            val parent = File(root, relativeParent)
            val destination = File(parent, fileName)
            assertArrayEquals(payload, destination.readBytes())
            assertTrue(parent.list().orEmpty().contains(fileName))
            assertNoReservedArtifacts(parent)
        }
    }

    private fun AtomicDirectory.write(
        relative: String,
        payload: ByteArray,
    ): AtomicWriteReceipt = openAtomicFileTransaction(
        relativeDestination = AtomicRelativePath.parse(relative),
        options = OPTIONS,
    ).use { transaction ->
        transaction.writeAndCommit { sink ->
            sink.write(payload)
        }.receipt
    }

    private inline fun withSmokeRoot(
        block: (root: File, directory: AtomicDirectory, relativeParent: String) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir
        check(root.isDirectory) {
            "Android application cache root must already exist"
        }
        val relativeParent = "native-io-smoke-${UUID.randomUUID()}"
        val createdParent = File(root, relativeParent)
        try {
            openAtomicDirectory(LocalPath(root.absolutePath)).use { directory ->
                block(root, directory, relativeParent)
            }
        } finally {
            createdParent.deleteRecursively()
        }
    }

    private fun assertOwnerOnlyFile(file: File) {
        val mode = Os.stat(file.absolutePath).st_mode
        assertEquals(0, mode and (OsConstants.S_IRWXG or OsConstants.S_IRWXO))
        assertTrue(mode and OsConstants.S_IRUSR != 0)
        assertTrue(mode and OsConstants.S_IWUSR != 0)
    }

    private fun assertOwnerOnlyDirectory(directory: File) {
        val mode = Os.stat(directory.absolutePath).st_mode
        assertEquals(0, mode and (OsConstants.S_IRWXG or OsConstants.S_IRWXO))
        assertTrue(mode and OsConstants.S_IRUSR != 0)
        assertTrue(mode and OsConstants.S_IWUSR != 0)
        assertTrue(mode and OsConstants.S_IXUSR != 0)
    }

    private fun assertNoReservedArtifacts(directory: File) {
        assertTrue(
            !directory.exists() ||
                directory.walkTopDown().none { file ->
                    isReservedTemporaryArtifactName(file.name)
                },
        )
    }

    private companion object {
        val OPTIONS = AtomicWriteOptions(
            publication = AtomicPublicationPolicy.Create(
                permissions = AtomicFilePermissions.OwnerOnly,
            ),
            parentDirectories = ParentDirectoryPolicy.CreateMissing(
                permissions = AtomicDirectoryPermissions.OwnerOnly,
            ),
            existingParentLinks = ExistingParentLinkPolicy.Reject,
            synchronization = SynchronizationPolicy.Required(
                SyncLevel.FileSynchronized,
            ),
        )
    }
}
