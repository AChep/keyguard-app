package com.artemchep.keyguard.util.io.atomic

import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.artifact.SweepReport
import com.artemchep.keyguard.util.io.artifact.SweepStatus
import com.artemchep.keyguard.util.io.artifact.TemporaryArtifactRole
import com.artemchep.keyguard.util.io.artifact.isReservedTemporaryArtifactName
import com.artemchep.keyguard.util.io.artifact.newFileLeaseTemporaryArtifactName
import com.artemchep.keyguard.util.io.artifact.sweepTemporaryArtifacts
import com.artemchep.keyguard.util.io.artifact.temporaryArtifactName
import com.artemchep.keyguard.util.io.bridge.ensureNativeIoAvailable
import com.artemchep.keyguard.util.io.toLocalPath
import kotlinx.io.writeString
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val UNIX_MODE_WITH_SPECIAL_BITS = 0x9ED // 04755
private const val UNIX_EXPECTED_BASIC_MODE = 0x1ED // 0755
private const val UNIX_PERMISSION_BITS_MASK = 0xFFF

class AtomicFileTransactionTest {
    @Test
    fun createPublishesCompletedFileAndRemovesTemporary() {
        withTempDirectory { root ->
            val destination = root.resolve("payload.bin")

            val result = writeFileAtomically(
                destination = destination.toLocalPath(),
                publication = AtomicPublicationPolicy.Create(
                    permissions = AtomicFilePermissions.OwnerOnly,
                ),
                parentDirectories = ParentDirectoryPolicy.RequireExisting,
            ) { sink ->
                sink.writeString("payload")
                assertTrue(Files.notExists(destination))
                "caller value"
            }

            assertEquals("caller value", result.value)
            assertEquals(
                AchievedSyncLevel.FileSynchronized,
                result.receipt.achievedSyncLevel,
            )
            assertEquals(AtomicPublicationState.Published, result.receipt.publicationState)
            assertEquals("payload", destination.readText())
            assertNoTemporaries(root)
        }
    }

    @Test
    fun everySupportedSynchronizationLevelPublishesAndReportsItself() {
        withTempDirectory { root ->
            for (level in SyncLevel.entries) {
                if (isWindowsHost() && level == SyncLevel.FileAndNamespaceSynchronized) {
                    continue
                }
                val destination = root.resolve("payload-$level.bin")
                val result = writeFileAtomically(
                    destination = destination.toLocalPath(),
                    publication = AtomicPublicationPolicy.Create(
                        permissions = AtomicFilePermissions.OwnerOnly,
                    ),
                    parentDirectories = ParentDirectoryPolicy.RequireExisting,
                    synchronization = SynchronizationPolicy.Required(level),
                ) { sink ->
                    sink.writeString("tiered payload")
                }
                // On a healthy local filesystem the requested level is achieved.
                assertEquals(level.name, result.receipt.achievedSyncLevel.name)
                assertEquals("tiered payload", destination.readText())
            }
            assertNoTemporaries(root)
        }
    }

    @Test
    fun sweeperReclaimsOrphanedArtifactsAndSparesUserFiles() {
        withTempDirectory { root ->
            val orphan = root.resolve(newFileLeaseTemporaryArtifactName(TemporaryArtifactRole.New))
            orphan.writeText("stale staged bytes")
            val userFile = root.resolve("vault.kdbx")
            userFile.writeText("precious")

            val report = sweepTemporaryArtifacts(
                directory = root.toLocalPath(),
                olderThan = kotlin.time.Duration.ZERO,
            )

            assertEquals(
                SweepReport(
                    status = SweepStatus.Complete,
                    entriesSeen = 2uL,
                    candidateNames = 1uL,
                    removed = 1uL,
                    skippedYoung = 0uL,
                    skippedBusy = 0uL,
                    skippedUnsafe = 0uL,
                    skippedChanged = 0uL,
                    inspectionFailed = 0uL,
                    removalFailed = 0uL,
                    firstFailure = null,
                ),
                report,
            )
            assertTrue(Files.notExists(orphan))
            assertEquals("precious", userFile.readText())
        }
    }

    @Test
    fun freshArtifactsSurviveTheSweeper() {
        withTempDirectory { root ->
            val fresh = root.resolve(newFileLeaseTemporaryArtifactName(TemporaryArtifactRole.New))
            fresh.writeText("live transaction")

            val report = sweepTemporaryArtifacts(
                directory = root.toLocalPath(),
                olderThan = kotlin.time.Duration.parse("1h"),
            )

            assertEquals(
                SweepReport(
                    status = SweepStatus.Complete,
                    entriesSeen = 1uL,
                    candidateNames = 1uL,
                    removed = 0uL,
                    skippedYoung = 1uL,
                    skippedBusy = 0uL,
                    skippedUnsafe = 0uL,
                    skippedChanged = 0uL,
                    inspectionFailed = 0uL,
                    removalFailed = 0uL,
                    firstFailure = null,
                ),
                report,
            )
            assertTrue(Files.exists(fresh))
        }
    }

    @Test
    fun uncoordinatedArtifactsAreReservedButNeverSwept() {
        withTempDirectory { root ->
            val uncoordinated = root.resolve(
                temporaryArtifactName(
                    role = TemporaryArtifactRole.New,
                    nonce = "123e4567-e89b-42d3-a456-426614174000",
                ),
            )
            uncoordinated.writeText("externally coordinated")

            val report = sweepTemporaryArtifacts(
                directory = root.toLocalPath(),
                olderThan = kotlin.time.Duration.ZERO,
            )

            assertEquals(1uL, report.entriesSeen)
            assertEquals(0uL, report.candidateNames)
            assertEquals(0uL, report.removed)
            assertTrue(Files.exists(uncoordinated))
        }
    }

    @Test
    fun nativeIoAvailabilityProbeSucceeds() {
        ensureNativeIoAvailable()
    }
}

class AtomicFilePermissionsTest {
    @Test
    fun replacePreservesTheDestinationPermissions() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        withTempDirectory { root ->
            val destination = root.resolve("payload.bin")
            destination.writeText("original")
            val permissions = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
            )
            Files.setPosixFilePermissions(destination, permissions)

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

            assertEquals(permissions, Files.getPosixFilePermissions(destination))
            assertEquals("replacement", destination.readText())
        }
    }

    @Test
    fun preservedReplacementUsesFallbackPermissionsWhenDestinationIsMissing() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        withTempDirectory { root ->
            val destination = root.resolve("payload.bin")

            writeFileAtomically(
                destination = destination.toLocalPath(),
                publication = AtomicPublicationPolicy.Replace(
                    access = ReplacementAccessPolicy.PreserveExistingBasicPermissions(
                        ifDestinationMissing = AtomicFilePermissions.OwnerOnly,
                    ),
                ),
                parentDirectories = ParentDirectoryPolicy.RequireExisting,
            ) { sink ->
                sink.writeString("replacement")
            }

            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
                Files.getPosixFilePermissions(destination),
            )
            assertEquals("replacement", destination.readText())
        }
    }

    @Test
    fun preservedReplacementRejectsSymbolicLinkDestination() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        withTempDirectory { root ->
            val target = root.resolve("target.bin")
            target.writeText("original")
            val destination = root.resolve("payload.bin")
            Files.createSymbolicLink(destination, target.fileName)

            assertFailsWith<AtomicFileWriteException> {
                writeFileAtomically(
                    destination = destination.toLocalPath(),
                    publication = AtomicPublicationPolicy.Replace(
                        access = ReplacementAccessPolicy.PreserveExistingBasicPermissions(
                            ifDestinationMissing = AtomicFilePermissions.OwnerOnly,
                        ),
                    ),
                    parentDirectories = ParentDirectoryPolicy.RequireExisting,
                ) { sink ->
                    sink.writeString("replacement")
                }
            }

            assertTrue(Files.isSymbolicLink(destination))
            assertEquals("original", target.readText())
            assertNoTemporaries(root)
        }
    }

    @Test
    fun preservedReplacementRejectsNonRegularDestination() {
        withTempDirectory { root ->
            val destination = root.resolve("payload")
            Files.createDirectory(destination)

            assertFailsWith<AtomicFileWriteException> {
                writeFileAtomically(
                    destination = destination.toLocalPath(),
                    publication = AtomicPublicationPolicy.Replace(
                        access = ReplacementAccessPolicy.PreserveExistingBasicPermissions(
                            ifDestinationMissing = AtomicFilePermissions.OwnerOnly,
                        ),
                    ),
                    parentDirectories = ParentDirectoryPolicy.RequireExisting,
                ) { sink ->
                    sink.writeString("replacement")
                }
            }

            assertTrue(Files.isDirectory(destination))
            assertNoTemporaries(root)
        }
    }

    @Test
    fun replacementDoesNotCarrySpecialModeBitsToNewContent() {
        if ("unix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        withTempDirectory { root ->
            val destination = root.resolve("payload.bin")
            destination.writeText("original")
            Files.setAttribute(destination, "unix:mode", UNIX_MODE_WITH_SPECIAL_BITS)

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

            val mode = Files.getAttribute(destination, "unix:mode") as Int
            assertEquals(UNIX_EXPECTED_BASIC_MODE, mode and UNIX_PERMISSION_BITS_MASK)
        }
    }

    @Test
    fun ownerOnlyReplaceNeverWidensAccessBeyondTheOwner() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        withTempDirectory { root ->
            val destination = root.resolve("payload.bin")
            destination.writeText("original")
            Files.setPosixFilePermissions(
                destination,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ,
                ),
            )

            writeFileAtomically(
                destination = destination.toLocalPath(),
                publication = AtomicPublicationPolicy.Replace(
                    access = ReplacementAccessPolicy.UseRequestedPermissions(
                        permissions = AtomicFilePermissions.OwnerOnly,
                    ),
                ),
                parentDirectories = ParentDirectoryPolicy.RequireExisting,
            ) { sink ->
                sink.writeString("replacement")
            }

            // Requested owner-only access deliberately ignores the replaced
            // destination's broader permissions.
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
                Files.getPosixFilePermissions(destination),
            )
            assertEquals("replacement", destination.readText())
        }
    }
}

class AtomicFilePublicationTest {
    @Test
    fun replacePublishesOnlyAfterSuccessfulWrite() {
        withTempDirectory { root ->
            val destination = root.resolve("payload.bin")
            destination.writeText("original")

            writeFileAtomically(
                destination = destination.toLocalPath(),
                publication = AtomicPublicationPolicy.Replace(
                    access = ReplacementAccessPolicy.UseRequestedPermissions(
                        permissions = AtomicFilePermissions.OwnerOnly,
                    ),
                ),
                parentDirectories = ParentDirectoryPolicy.RequireExisting,
            ) { sink ->
                sink.writeString("replacement")
                assertEquals("original", destination.readText())
            }

            assertEquals("replacement", destination.readText())
            assertNoTemporaries(root)
        }
    }

    @Test
    fun failedWriteKeepsDestinationAndRemovesTemporary() {
        withTempDirectory { root ->
            val destination = root.resolve("payload.bin")
            destination.writeText("original")

            assertFailsWith<IllegalStateException> {
                writeFileAtomically(
                    destination = destination.toLocalPath(),
                    publication = AtomicPublicationPolicy.Replace(
                        access = ReplacementAccessPolicy.UseRequestedPermissions(
                            permissions = AtomicFilePermissions.OwnerOnly,
                        ),
                    ),
                    parentDirectories = ParentDirectoryPolicy.RequireExisting,
                ) { sink ->
                    sink.writeString("abandoned")
                    error("write failed")
                }
            }

            assertEquals("original", destination.readText())
            assertNoTemporaries(root)
        }
    }

    @Test
    fun createNeverClobbersAnExistingDestination() {
        withTempDirectory { root ->
            val destination = root.resolve("payload.bin")
            destination.writeText("original")

            assertFailsWith<AtomicDestinationExistsException> {
                writeFileAtomically(
                    destination = destination.toLocalPath(),
                    publication = AtomicPublicationPolicy.Create(
                        permissions = AtomicFilePermissions.ProcessDefault,
                    ),
                    parentDirectories = ParentDirectoryPolicy.RequireExisting,
                ) { sink ->
                    sink.writeString("new")
                }
            }

            assertEquals("original", destination.readText())
            assertNoTemporaries(root)
        }
    }

    @Test
    fun ownerOnlyTemporaryUsesOwnerReadWritePermissions() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        withTempDirectory { root ->
            val destination = root.resolve("payload.bin")
            writeFileAtomically(
                destination = destination.toLocalPath(),
                publication = AtomicPublicationPolicy.Create(
                    permissions = AtomicFilePermissions.OwnerOnly,
                ),
                parentDirectories = ParentDirectoryPolicy.RequireExisting,
            ) { sink ->
                sink.writeString("payload")
                val temporary = root.listDirectoryEntries().single()
                assertTrue(isReservedTemporaryArtifactName(temporary.fileName.toString()))
                assertEquals(
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    ),
                    Files.getPosixFilePermissions(temporary),
                )
            }
        }
    }

    @Test
    fun durableWriteCreatesAndPersistsMissingParentChain() {
        if (isWindowsHost()) return
        withTempDirectory { root ->
            val destination = root.resolve("accounts/current/payload.bin")

            val result = writeFileAtomically(
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

            assertEquals(
                AchievedSyncLevel.FileAndNamespaceSynchronized,
                result.receipt.achievedSyncLevel,
            )
            assertEquals("payload", destination.readText())
            val directoryPermissions = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
            assertEquals(
                directoryPermissions,
                Files.getPosixFilePermissions(root.resolve("accounts")),
            )
            assertEquals(
                directoryPermissions,
                Files.getPosixFilePermissions(destination.parent),
            )
            assertNoTemporaries(destination.parent)
        }
    }
}

class AtomicRetainedDirectoryTest {
    @OptIn(InternalKeyguardIoApi::class)
    @Test
    fun retainedDirectoryTransactionSurvivesDirectoryClose() {
        withTempDirectory { root ->
            val directory = openAtomicDirectory(root.toLocalPath())
            val transaction = directory.openAtomicFileTransaction(
                relativeDestination = AtomicRelativePath.parse("nested/payload.bin"),
                options = retainedDirectoryOptions(),
            )
            directory.close()
            directory.close()

            transaction.use {
                it.writeAndCommit { sink ->
                    sink.writeString("retained")
                }
            }

            assertEquals("retained", root.resolve("nested/payload.bin").readText())
        }
    }

    @Test
    fun retainedDestinationCreatesMultipleMissingDescendantsOnFirstWrite() {
        withTempDirectory { root ->
            val destination = AtomicFileDestination(
                root = root.toLocalPath(),
                relativePath = AtomicRelativePath.parse(
                    "missing-one/missing-two/дані.bin",
                ),
            )

            writeFileAtomically(
                destination = destination,
                options = retainedDirectoryOptions(),
            ) { sink ->
                sink.writeString("first write")
            }

            assertEquals(
                "first write",
                root.resolve("missing-one/missing-two/дані.bin").readText(),
            )
        }
    }

    @OptIn(InternalKeyguardIoApi::class)
    @Test
    fun retainedDirectoryPinsRootAcrossSymlinkRetarget() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        withTempDirectory { parent ->
            val trusted = Files.createDirectory(parent.resolve("trusted"))
            val evil = Files.createDirectory(parent.resolve("evil"))
            val selected = parent.resolve("selected")
            Files.createSymbolicLink(selected, trusted.fileName)
            val directory = openAtomicDirectory(selected.toLocalPath())
            Files.move(selected, parent.resolve("selected-old"))
            Files.createSymbolicLink(selected, evil.fileName)

            directory.use {
                it.openAtomicFileTransaction(
                    relativeDestination = AtomicRelativePath.parse("payload.bin"),
                    options = retainedDirectoryOptions(
                        parentDirectories = ParentDirectoryPolicy.RequireExisting,
                    ),
                ).use { transaction ->
                    transaction.writeAndCommit { sink ->
                        sink.writeString("trusted")
                    }
                }
            }

            assertEquals("trusted", trusted.resolve("payload.bin").readText())
            assertTrue(Files.notExists(evil.resolve("payload.bin")))
        }
    }

    @OptIn(InternalKeyguardIoApi::class)
    @Test
    fun retainedDirectoryRejectsLinkedDescendant() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        withTempDirectory { parent ->
            val root = Files.createDirectory(parent.resolve("root"))
            val outside = Files.createDirectory(parent.resolve("outside"))
            Files.createSymbolicLink(root.resolve("linked"), outside)

            openAtomicDirectory(root.toLocalPath()).use { directory ->
                assertFailsWith<AtomicFileWriteException> {
                    directory.openAtomicFileTransaction(
                        relativeDestination = AtomicRelativePath.parse("linked/payload.bin"),
                        options = retainedDirectoryOptions(
                            parentDirectories = ParentDirectoryPolicy.RequireExisting,
                        ),
                    )
                }
            }
            assertTrue(Files.notExists(outside.resolve("payload.bin")))
        }
    }
}

private fun assertNoTemporaries(root: Path) {
    assertTrue(
        root.listDirectoryEntries().none {
            isReservedTemporaryArtifactName(it.fileName.toString())
        },
    )
}

private fun isWindowsHost(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

private inline fun withTempDirectory(block: (Path) -> Unit) {
    val root = createTempDirectory("keyguard-atomic-file")
    try {
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}

private fun retainedDirectoryOptions(
    parentDirectories: ParentDirectoryPolicy = ParentDirectoryPolicy.CreateMissing(
        permissions = AtomicDirectoryPermissions.OwnerOnly,
    ),
): AtomicWriteOptions = AtomicWriteOptions(
    publication = AtomicPublicationPolicy.Create(
        permissions = AtomicFilePermissions.OwnerOnly,
    ),
    parentDirectories = parentDirectories,
    existingParentLinks = ExistingParentLinkPolicy.Reject,
    synchronization = SynchronizationPolicy.Required(SyncLevel.FileSynchronized),
)

private fun <T> writeFileAtomically(
    destination: com.artemchep.keyguard.util.io.LocalPath,
    publication: AtomicPublicationPolicy,
    parentDirectories: ParentDirectoryPolicy,
    existingParentLinks: ExistingParentLinkPolicy =
        ExistingParentLinkPolicy.FollowAndPin,
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
