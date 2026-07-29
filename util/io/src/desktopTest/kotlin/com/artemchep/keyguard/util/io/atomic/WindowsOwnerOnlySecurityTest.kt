package com.artemchep.keyguard.util.io.atomic

import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.toLocalPath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryPermission.DELETE
import java.nio.file.attribute.AclEntryPermission.READ_DATA
import java.nio.file.attribute.AclEntryPermission.WRITE_DATA
import java.nio.file.attribute.AclEntryType.ALLOW
import java.nio.file.attribute.AclFileAttributeView
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.io.writeString

class WindowsOwnerOnlySecurityTest {
    @Test
    fun ownerOnlyAtomicWritePublishesAnOwnerOnlyDacl() {
        if (!isWindowsHost()) return

        val directory = createTempDirectory("keyguard-windows-private-file")
        try {
            val destination = directory.resolve("payload.bin")
            writeFileAtomically(
                destination = destination.toLocalPath(),
                publication = AtomicPublicationPolicy.Create(
                    permissions = AtomicFilePermissions.OwnerOnly,
                ),
                parentDirectories = ParentDirectoryPolicy.RequireExisting,
            ) { sink ->
                sink.writeString("payload")
            }
            assertWindowsOwnerOnlyDacl(destination)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun replacementPreservesTheExistingDacl() {
        if (!isWindowsHost()) return

        val directory = createTempDirectory("keyguard-windows-preserved-dacl")
        try {
            val destination = directory.resolve("payload.bin")
            writeFileAtomically(
                destination = destination.toLocalPath(),
                publication = AtomicPublicationPolicy.Create(
                    permissions = AtomicFilePermissions.OwnerOnly,
                ),
                parentDirectories = ParentDirectoryPolicy.RequireExisting,
            ) { sink ->
                sink.writeString("original")
            }

            writeFileAtomically(
                destination = destination.toLocalPath(),
                publication = AtomicPublicationPolicy.Replace(
                    access = ReplacementAccessPolicy
                        .PreserveExistingBasicPermissions(
                            ifDestinationMissing =
                                AtomicFilePermissions.ProcessDefault,
                        ),
                ),
                parentDirectories = ParentDirectoryPolicy.RequireExisting,
            ) { sink ->
                sink.writeString("replacement")
            }

            assertWindowsOwnerOnlyDacl(destination)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun replacementPreservesAProcessDefaultInheritedDacl() {
        if (!isWindowsHost()) return

        val directory = createTempDirectory("keyguard-windows-inherited-dacl")
        try {
            val destination = directory.resolve("payload.bin")
            Files.writeString(destination, "original")
            val originalAcl = readAcl(destination)

            writeFileAtomically(
                destination = destination.toLocalPath(),
                publication = AtomicPublicationPolicy.Replace(
                    access = ReplacementAccessPolicy
                        .PreserveExistingBasicPermissions(
                            ifDestinationMissing =
                                AtomicFilePermissions.ProcessDefault,
                        ),
                ),
                parentDirectories = ParentDirectoryPolicy.RequireExisting,
            ) { sink ->
                sink.writeString("replacement")
            }

            assertEquals(originalAcl, readAcl(destination))
            assertEquals("replacement", Files.readString(destination))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun durableWriteRejectsMissingParentsBeforePublication() {
        if (!isWindowsHost()) return

        val directory = createTempDirectory("keyguard-windows-parent-durability")
        try {
            val destination = directory.resolve("missing/nested/payload.bin")
            val error = assertFailsWith<AtomicFileWriteException> {
                writeFileAtomically(
                    destination = destination.toLocalPath(),
                    publication = AtomicPublicationPolicy.Create(
                        permissions = AtomicFilePermissions.OwnerOnly,
                    ),
                    parentDirectories = ParentDirectoryPolicy.CreateMissing(
                        permissions = AtomicDirectoryPermissions.OwnerOnly,
                    ),
                    synchronization = SynchronizationPolicy.Required(
                        SyncLevel.FileAndNamespaceSynchronized,
                    ),
                ) { sink ->
                    sink.writeString("payload")
                }
            }

            assertEquals(AtomicPublicationState.NotPublished, error.publicationState)
            assertEquals(FileSystemFailureKind.DurabilityUnavailable, error.failure.kind)
            assertTrue(Files.notExists(destination))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun isWindowsHost(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private fun assertWindowsOwnerOnlyDacl(path: Path) {
        val owner = Files.getOwner(path)
        val acl = readAcl(path)

        assertEquals(1, acl.size)
        with(acl.single()) {
            assertEquals(ALLOW, type())
            assertEquals(owner, principal())
            assertTrue(flags().isEmpty())
            assertTrue(READ_DATA in permissions())
            assertTrue(WRITE_DATA in permissions())
            assertTrue(DELETE in permissions())
        }
    }

    private fun readAcl(path: Path) = checkNotNull(
        Files.getFileAttributeView(path, AclFileAttributeView::class.java),
    ).acl
}

private fun <T> writeFileAtomically(
    destination: com.artemchep.keyguard.util.io.LocalPath,
    publication: AtomicPublicationPolicy,
    parentDirectories: ParentDirectoryPolicy,
    existingParentLinks: ExistingParentLinkPolicy =
        ExistingParentLinkPolicy.Reject,
    synchronization: SynchronizationPolicy = SynchronizationPolicy.Required(
        SyncLevel.FileSynchronized,
    ),
    write: (kotlinx.io.Sink) -> T,
): AtomicWriteResult<T> = writeFileAtomically(
    destination = destination,
    options = AtomicWriteOptions(
        publication = publication,
        parentDirectories = parentDirectories,
        existingParentLinks = existingParentLinks,
        synchronization = synchronization,
    ),
    write = write,
)
