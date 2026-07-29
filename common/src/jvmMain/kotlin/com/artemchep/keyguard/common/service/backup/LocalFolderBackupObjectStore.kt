package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.FileSystemOperationException
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.artifact.TemporaryArtifactRole
import com.artemchep.keyguard.util.io.artifact.newTemporaryArtifactName
import com.artemchep.keyguard.util.io.atomic.AtomicDestinationExistsException
import com.artemchep.keyguard.util.io.atomic.AtomicDirectory
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryPermissions
import com.artemchep.keyguard.util.io.atomic.AtomicFilePermissions
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationPolicy
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationUnknownException
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationUnsupportedException
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.atomic.AtomicSynchronizationException
import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.ExistingParentLinkPolicy
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.ReplacementAccessPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.toJavaFile
import com.artemchep.keyguard.util.io.toLocalPathFromFileUriOrNull
import com.artemchep.keyguard.util.io.withBufferedSink
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.attribute.BasicFileAttributes
import kotlin.time.Instant
import com.artemchep.keyguard.util.io.atomic.openAtomicDirectory as openNativeAtomicDirectory

@OptIn(InternalKeyguardIoApi::class)
class LocalFolderBackupObjectStore : BackupObjectStore {
    private val root: LocalPath
    private val atomicMove: ((Path, Path, BackupWriteMode) -> Unit)?
    private val openInput: (Path) -> InputStream
    private val atomicDirectoryOpen: (LocalPath) -> AtomicDirectory

    constructor(
        root: LocalPath,
    ) : this(
        root = root,
        atomicMove = null,
        openInput = { path -> Files.newInputStream(path) },
        atomicDirectoryOpen = ::openNativeAtomicDirectory,
    )

    internal constructor(
        root: LocalPath,
        openInput: (Path) -> InputStream,
    ) : this(
        root = root,
        atomicMove = null,
        openInput = openInput,
        atomicDirectoryOpen = ::openNativeAtomicDirectory,
    )

    internal constructor(
        root: LocalPath,
        atomicMove: (Path, Path) -> Unit,
    ) : this(
        root = root,
        atomicMove = { source, target, _ -> atomicMove(source, target) },
        openInput = { path -> Files.newInputStream(path) },
        atomicDirectoryOpen = ::openNativeAtomicDirectory,
    )

    @OptIn(InternalKeyguardIoApi::class)
    internal constructor(
        root: LocalPath,
        openInput: (Path) -> InputStream,
        atomicDirectoryOpen: (LocalPath) -> AtomicDirectory,
    ) : this(
        root = root,
        atomicMove = null,
        openInput = openInput,
        atomicDirectoryOpen = atomicDirectoryOpen,
    )

    private constructor(
        root: LocalPath,
        atomicMove: ((Path, Path, BackupWriteMode) -> Unit)?,
        openInput: (Path) -> InputStream,
        atomicDirectoryOpen: (LocalPath) -> AtomicDirectory,
    ) {
        this.root = root
        this.atomicMove = atomicMove
        this.openInput = openInput
        this.atomicDirectoryOpen = atomicDirectoryOpen
    }

    override val capabilities: BackupObjectStoreCapabilities = BackupObjectStoreCapabilities(
        atomicWholeObjectWrite = true,
        atomicReplace = true,
        rangeRead = true,
        strongReadAfterWrite = true,
        strongListAfterWrite = true,
    )

    override suspend fun stat(
        key: BackupObjectKey,
    ): BackupObjectInfo? = readObjectInfoOrNull(
        key = key,
        file = resolve(key),
        operation = BackupObjectStoreOperation.Stat,
    )

    override suspend fun read(
        key: BackupObjectKey,
        range: BackupByteRange?,
    ): Source {
        val file = resolve(key)
        val attributes = readRegularFileAttributes(
            key = key,
            file = file,
            operation = BackupObjectStoreOperation.Read,
        )
        if (range != null && !attributes.contains(range)) {
            throw BackupObjectStoreException.InvalidRange(
                key = key,
                range = range,
            )
        }

        val input = openInputForRead(key, file)
        try {
            if (range != null && range.offset > 0L) {
                skipFully(input, range.offset)
            }
            val source = when (val length = range?.length) {
                null -> input
                else -> BoundedInputStream(input, length)
            }
            return source.toBackupReadSource(key)
        } catch (e: EOFException) {
            input.close()
            throw requireNotNull(range) {
                "EOF while reading an unbounded backup object."
            }.let { invalidRange ->
                BackupObjectStoreException.InvalidRange(
                    key = key,
                    range = invalidRange,
                    cause = e,
                )
            }
        } catch (e: Exception) {
            input.close()
            throw e
        }
    }

    override suspend fun write(
        key: BackupObjectKey,
        mode: BackupWriteMode,
        write: suspend (kotlinx.io.Sink) -> Unit,
    ): BackupObjectInfo {
        var tempFile: File? = null
        return try {
            atomicMove?.let { injectedAtomicMove ->
                writeWithInjectedAtomicMove(
                    key = key,
                    mode = mode,
                    write = write,
                    atomicMove = injectedAtomicMove,
                    onStaged = { staged -> tempFile = staged },
                )
            } ?: writeWithAtomicDirectory(
                key = key,
                mode = mode,
                write = write,
            )
        } catch (e: Exception) {
            tempFile?.delete()
            throw writeFailure(
                key = key,
                mode = mode,
                cause = e,
                isReadOnlyFileSystem = ::isReadOnlyFileSystem,
            )
        }
    }

    private suspend fun writeWithInjectedAtomicMove(
        key: BackupObjectKey,
        mode: BackupWriteMode,
        write: suspend (kotlinx.io.Sink) -> Unit,
        atomicMove: (Path, Path, BackupWriteMode) -> Unit,
        onStaged: (File) -> Unit,
    ): BackupObjectInfo {
        val file = resolve(key)
        readAttributesOrNull(
            file = file,
            operation = BackupObjectStoreOperation.Write,
            key = key,
        )?.let { existingAttributes ->
            if (!existingAttributes.isRegularFile || mode == BackupWriteMode.Create) {
                throw BackupObjectStoreException.AlreadyExists(key)
            }
        }
        val parent = requireNotNull(file.parentFile)
        try {
            parent.mkdirs()
        } catch (e: SecurityException) {
            throw BackupObjectStoreException.PermissionDenied(
                operation = BackupObjectStoreOperation.Write,
                key = key,
                cause = e,
            )
        }
        val staged = File(
            parent,
            newTemporaryArtifactName(TemporaryArtifactRole.New),
        )
        onStaged(staged)
        FileOutputStream(staged).use { output ->
            output.withBufferedSink(write)
        }
        atomicMove(staged.toPath(), file.toPath(), mode)
        return requireNotNull(stat(key)) {
            "Backup object '${key.value}' was not visible after writing."
        }
    }

    private suspend fun writeWithAtomicDirectory(
        key: BackupObjectKey,
        mode: BackupWriteMode,
        write: suspend (kotlinx.io.Sink) -> Unit,
    ): BackupObjectInfo {
        val atomicWriteResult = atomicDirectoryOpen(root).use { directory ->
            directory.openAtomicFileTransaction(
                relativeDestination = AtomicRelativePath.parse(key.value),
                options = AtomicWriteOptions(
                    publication = atomicPublicationPolicy(mode),
                    parentDirectories = ParentDirectoryPolicy.CreateMissing(
                        permissions = AtomicDirectoryPermissions.ProcessDefault,
                    ),
                    existingParentLinks = ExistingParentLinkPolicy.Reject,
                    synchronization = SynchronizationPolicy.Prefer(
                        preferred = SyncLevel.FileAndNamespaceSynchronized,
                        minimum = SyncLevel.FileSynchronized,
                    ),
                ),
            ).use { transaction ->
                transaction.writeAndCommitSuspending { transactionSink ->
                    val countingSink = CountingRawSink(transactionSink)
                    countingSink.buffered().use { sink ->
                        write(sink)
                    }
                    countingSink.bytesWritten
                }
            }
        }
        return BackupObjectInfo(
            key = key,
            size = atomicWriteResult.value,
            updatedAt = null,
            atomicWriteReceipt = atomicWriteResult.receipt,
        )
    }

    private fun atomicPublicationPolicy(
        mode: BackupWriteMode,
    ): AtomicPublicationPolicy = when (mode) {
        BackupWriteMode.Create -> AtomicPublicationPolicy.Create(
            permissions = AtomicFilePermissions.ProcessDefault,
        )

        BackupWriteMode.CreateOrReplace -> AtomicPublicationPolicy.Replace(
            access = ReplacementAccessPolicy.PreserveExistingBasicPermissions(
                ifDestinationMissing = AtomicFilePermissions.ProcessDefault,
            ),
        )
    }

    private fun isReadOnlyFileSystem(
        key: BackupObjectKey,
    ): Boolean {
        var path: Path? = resolve(key)
            .toPath()
            .toAbsolutePath()
        while (path != null && !Files.exists(path)) {
            path = path.parent
        }
        return path?.let { existingPath ->
            runCatching {
                Files.getFileStore(existingPath).isReadOnly
            }.getOrDefault(false)
        } ?: false
    }

    override suspend fun list(
        prefix: BackupObjectKeyPrefix,
        cursor: BackupListCursor?,
    ): BackupObjectListPage {
        try {
            val rootFile = root.toJavaFile()
            if (!directoryExists(rootFile)) {
                return BackupObjectListPage(emptyList())
            }

            val items = rootFile
                .walkTopDown()
                .mapNotNull { file ->
                    val keyValue = file
                        .toRelativeString(rootFile)
                        .replace(File.separatorChar, '/')
                    if (keyValue.isEmpty() || !keyValue.startsWith(prefix.value)) {
                        return@mapNotNull null
                    }
                    val key = BackupObjectKey(keyValue)
                    readObjectInfoOrNull(
                        key = key,
                        file = file,
                        operation = BackupObjectStoreOperation.List,
                    )
                }
                .sortedBy { it.key.value }
                .toList()
            return BackupObjectListPage(items)
        } catch (e: SecurityException) {
            throw BackupObjectStoreException.PermissionDenied(
                operation = BackupObjectStoreOperation.List,
                cause = e,
            )
        }
    }

    override suspend fun delete(
        key: BackupObjectKey,
    ) {
        try {
            val file = resolve(key)
            val deleted = Files.deleteIfExists(file.toPath())
            if (deleted) {
                deleteEmptyParents(file.parentFile)
            }
        } catch (e: AccessDeniedException) {
            throw BackupObjectStoreException.PermissionDenied(
                operation = BackupObjectStoreOperation.Delete,
                key = key,
                cause = e,
            )
        } catch (e: IOException) {
            throw BackupObjectStoreException.Transient(
                operation = BackupObjectStoreOperation.Delete,
                key = key,
                cause = e,
            )
        } catch (e: SecurityException) {
            throw BackupObjectStoreException.PermissionDenied(
                operation = BackupObjectStoreOperation.Delete,
                key = key,
                cause = e,
            )
        }
    }

    private fun resolve(
        key: BackupObjectKey,
    ): File = root
        .toJavaFile()
        .resolve(key.value)

    private fun deleteEmptyParents(
        start: File?,
    ) {
        val rootFile = root.toJavaFile().absoluteFile
        var file = start?.absoluteFile
        while (file != null && file != rootFile && file.isDirectory && file.list().orEmpty().isEmpty()) {
            file.delete()
            file = file.parentFile
        }
    }

    private fun openInputForRead(
        key: BackupObjectKey,
        file: File,
    ): InputStream = try {
        openInput(file.toPath())
    } catch (e: NoSuchFileException) {
        throw BackupObjectStoreException.NotFound(
            key = key,
            operation = BackupObjectStoreOperation.Read,
            cause = e,
        )
    } catch (e: AccessDeniedException) {
        throw BackupObjectStoreException.PermissionDenied(
            operation = BackupObjectStoreOperation.Read,
            key = key,
            cause = e,
        )
    } catch (e: FileNotFoundException) {
        val attributes = readAttributesOrNull(
            file = file,
            operation = BackupObjectStoreOperation.Read,
            key = key,
        )
        if (attributes == null || !attributes.isRegularFile) {
            throw BackupObjectStoreException.NotFound(
                key = key,
                operation = BackupObjectStoreOperation.Read,
                cause = e,
            )
        }
        throw BackupObjectStoreException.PermissionDenied(
            operation = BackupObjectStoreOperation.Read,
            key = key,
            cause = e,
        )
    } catch (e: IOException) {
        throw BackupObjectStoreException.Transient(
            operation = BackupObjectStoreOperation.Read,
            key = key,
            cause = e,
        )
    } catch (e: SecurityException) {
        throw BackupObjectStoreException.PermissionDenied(
            operation = BackupObjectStoreOperation.Read,
            key = key,
            cause = e,
        )
    }

    private fun InputStream.toBackupReadSource(
        key: BackupObjectKey,
    ): Source {
        val upstream = asSource()
        return object : RawSource {
            override fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long = mapReadSourceException(key) {
                upstream.readAtMostTo(sink, byteCount)
            }

            override fun close() {
                mapReadSourceException(key) {
                    upstream.close()
                }
            }
        }.buffered()
    }

    private inline fun <T> mapReadSourceException(
        key: BackupObjectKey,
        block: () -> T,
    ): T = try {
        block()
    } catch (e: BackupObjectStoreException) {
        throw e
    } catch (e: AccessDeniedException) {
        throw BackupObjectStoreException.PermissionDenied(
            operation = BackupObjectStoreOperation.Read,
            key = key,
            cause = e,
        )
    } catch (e: IOException) {
        throw BackupObjectStoreException.Transient(
            operation = BackupObjectStoreOperation.Read,
            key = key,
            cause = e,
        )
    } catch (e: SecurityException) {
        throw BackupObjectStoreException.PermissionDenied(
            operation = BackupObjectStoreOperation.Read,
            key = key,
            cause = e,
        )
    }

    private fun readRegularFileAttributes(
        key: BackupObjectKey,
        file: File,
        operation: BackupObjectStoreOperation,
    ): BasicFileAttributes {
        val attributes = readAttributesOrNull(
            file = file,
            operation = operation,
            key = key,
        ) ?: throw BackupObjectStoreException.NotFound(
            key = key,
            operation = operation,
        )
        if (!attributes.isRegularFile) {
            throw BackupObjectStoreException.NotFound(
                key = key,
                operation = operation,
            )
        }
        return attributes
    }

    private fun readObjectInfoOrNull(
        key: BackupObjectKey,
        file: File,
        operation: BackupObjectStoreOperation,
    ): BackupObjectInfo? {
        val attributes = readAttributesOrNull(
            file = file,
            operation = operation,
            key = key,
        ) ?: return null
        if (!attributes.isRegularFile) {
            return null
        }
        return BackupObjectInfo(
            key = key,
            size = attributes.size(),
            updatedAt = Instant.fromEpochMilliseconds(attributes.lastModifiedTime().toMillis()),
        )
    }

    private fun directoryExists(
        file: File,
    ): Boolean {
        val attributes = readAttributesOrNull(
            file = file,
            operation = BackupObjectStoreOperation.List,
            key = null,
        ) ?: return false
        return attributes.isDirectory
    }

    private fun readAttributesOrNull(
        file: File,
        operation: BackupObjectStoreOperation,
        key: BackupObjectKey?,
    ): BasicFileAttributes? = try {
        Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
        )
    } catch (_: NoSuchFileException) {
        null
    } catch (e: AccessDeniedException) {
        throw BackupObjectStoreException.PermissionDenied(
            operation = operation,
            key = key,
            cause = e,
        )
    } catch (e: IOException) {
        throw BackupObjectStoreException.Transient(
            operation = operation,
            key = key,
            cause = e,
        )
    } catch (e: SecurityException) {
        throw BackupObjectStoreException.PermissionDenied(
            operation = operation,
            key = key,
            cause = e,
        )
    }

    private fun skipFully(
        input: InputStream,
        bytes: Long,
    ) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped <= 0L) {
                if (input.read() == -1) {
                    throw EOFException()
                }
                remaining -= 1L
            } else {
                remaining -= skipped
            }
        }
    }

    private fun BasicFileAttributes.contains(
        range: BackupByteRange,
    ): Boolean {
        val fileSize = size()
        if (range.offset > fileSize) {
            return false
        }
        val rangeLength = range.length
            ?: return true
        return rangeLength <= fileSize - range.offset
    }

    private class BoundedInputStream(
        input: InputStream,
        private var remaining: Long,
    ) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0L) {
                return -1
            }
            val result = super.read()
            if (result >= 0) {
                remaining -= 1L
            }
            return result
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (remaining <= 0L) {
                return -1
            }
            val limitedLength = minOf(len.toLong(), remaining).toInt()
            val read = super.read(b, off, limitedLength)
            if (read > 0) {
                remaining -= read.toLong()
            }
            return read
        }
    }
}

private class CountingRawSink(
    private val delegate: RawSink,
) : RawSink {
    var bytesWritten: Long = 0L
        private set

    override fun write(source: Buffer, byteCount: Long) {
        delegate.write(source, byteCount)
        bytesWritten = Math.addExact(bytesWritten, byteCount)
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}

/**
 * Translates a staging or publication failure into the store's vocabulary,
 * rethrowing anything that has no backup-specific meaning.
 */
private fun writeFailure(
    key: BackupObjectKey,
    mode: BackupWriteMode,
    cause: Exception,
    isReadOnlyFileSystem: (BackupObjectKey) -> Boolean,
): Exception {
    val alreadyExists = cause is AtomicDestinationExistsException ||
        (cause is FileAlreadyExistsException && mode == BackupWriteMode.Create)
    if (alreadyExists) {
        return BackupObjectStoreException.AlreadyExists(
            key = key,
            cause = cause,
        )
    }
    return when (cause) {
        is AtomicPublicationUnknownException -> publicationUnknownWriteFailure(key, cause)

        is AtomicSynchronizationException -> publishedSynchronizationUnknownWriteFailure(
            key = key,
            cause = cause,
        )

        is AtomicPublicationUnsupportedException -> unsupportedWriteFailure(key, cause)

        is FileSystemOperationException -> fileSystemOperationWriteFailure(
            key = key,
            cause = cause,
        )

        is AccessDeniedException,
        is FileNotFoundException,
        is SecurityException,
        is ReadOnlyFileSystemException,
        -> permissionDeniedWriteFailure(
            key = key,
            cause = cause,
        )

        is AtomicMoveNotSupportedException,
        is UnsupportedOperationException,
        -> unsupportedWriteFailure(key, cause)

        is FileAlreadyExistsException -> transientWriteFailure(
            key = key,
            cause = cause,
        )

        is FileSystemException -> fileSystemWriteFailure(
            key = key,
            cause = cause,
            isReadOnlyFileSystem = isReadOnlyFileSystem,
        )

        is IOException -> transientWriteFailure(
            key = key,
            cause = cause,
        )

        else -> cause
    }
}

private fun publicationUnknownWriteFailure(
    key: BackupObjectKey,
    cause: Exception,
) = BackupObjectStoreException.PublicationUnknown(
    key = key,
    cause = cause,
)

private fun publishedSynchronizationUnknownWriteFailure(
    key: BackupObjectKey,
    cause: AtomicSynchronizationException,
) = BackupObjectStoreException.PublishedSynchronizationUnknown(
    key = key,
    achievedSyncLevel = cause.achievedSyncLevel,
    cleanupIncomplete = cause.cleanupIncomplete,
    cause = cause,
)

private fun unsupportedWriteFailure(
    key: BackupObjectKey,
    cause: Exception,
) = BackupObjectStoreException.AtomicWriteUnsupported(
    key = key,
    cause = cause,
)

private fun fileSystemOperationWriteFailure(
    key: BackupObjectKey,
    cause: FileSystemOperationException,
): Exception = when (cause.failure.kind) {
    FileSystemFailureKind.PermissionDenied,
    FileSystemFailureKind.ReadOnlyFilesystem,
    -> permissionDeniedWriteFailure(key = key, cause = cause)

    FileSystemFailureKind.Unsupported ->
        BackupObjectStoreException.AtomicWriteUnsupported(
            key = key,
            cause = cause,
        )

    FileSystemFailureKind.InvalidInput,
    FileSystemFailureKind.Internal,
    -> cause

    else -> transientWriteFailure(key = key, cause = cause)
}

private fun fileSystemWriteFailure(
    key: BackupObjectKey,
    cause: FileSystemException,
    isReadOnlyFileSystem: (BackupObjectKey) -> Boolean,
): Exception = if (isReadOnlyFileSystem(key)) {
    permissionDeniedWriteFailure(key = key, cause = cause)
} else {
    transientWriteFailure(key = key, cause = cause)
}

private fun permissionDeniedWriteFailure(
    key: BackupObjectKey,
    cause: Exception,
) = BackupObjectStoreException.PermissionDenied(
    operation = BackupObjectStoreOperation.Write,
    key = key,
    cause = cause,
)

private fun transientWriteFailure(
    key: BackupObjectKey,
    cause: Exception,
) = BackupObjectStoreException.Transient(
    operation = BackupObjectStoreOperation.Write,
    key = key,
    cause = cause,
)

class LocalFolderBackupObjectStoreFactory : BackupObjectStoreFactory {
    override suspend fun open(
        store: BackupStoreConfig,
    ): BackupObjectStore {
        val localStore = requireNotNull(store as? BackupStoreConfig.Local) {
            "Backup local store configuration is required."
        }
        val repositoryPath = requireNotNull(localStore.path) {
            "Backup repository path is not configured."
        }
        val root = repositoryPath.toBackupLocalPath()
        Files.createDirectories(root.toJavaFile().toPath())
        return LocalFolderBackupObjectStore(root = root)
    }

    private fun String.toBackupLocalPath(): LocalPath =
        toLocalPathFromFileUriOrNull()
            ?: LocalPath(this)
}
