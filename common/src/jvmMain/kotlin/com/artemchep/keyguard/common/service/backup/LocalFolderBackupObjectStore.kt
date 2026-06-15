package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.io.withBufferedSink
import com.artemchep.keyguard.common.service.file.toLocalPathFromFileUriOrNull
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toJavaFile
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.CopyOption
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.time.Instant
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered

class LocalFolderBackupObjectStore : BackupObjectStore {
    private val root: LocalPath
    private val atomicMove: (Path, Path, BackupWriteMode) -> Unit
    private val openInput: (Path) -> InputStream

    constructor(
        root: LocalPath,
    ) : this(
        root = root,
        atomicMove = { source, target, mode ->
            Files.move(
                source,
                target,
                *atomicMoveOptions(mode),
            )
        },
        openInput = { path -> Files.newInputStream(path) },
    )

    internal constructor(
        root: LocalPath,
        openInput: (Path) -> InputStream,
    ) : this(
        root = root,
        atomicMove = { source, target, mode ->
            Files.move(
                source,
                target,
                *atomicMoveOptions(mode),
            )
        },
        openInput = openInput,
    )

    internal constructor(
        root: LocalPath,
        atomicMove: (Path, Path) -> Unit,
    ) : this(
        root = root,
        atomicMove = { source, target, _ -> atomicMove(source, target) },
        openInput = { path -> Files.newInputStream(path) },
    )

    private constructor(
        root: LocalPath,
        atomicMove: (Path, Path, BackupWriteMode) -> Unit,
        openInput: (Path) -> InputStream,
    ) {
        this.root = root
        this.atomicMove = atomicMove
        this.openInput = openInput
    }

    override val capabilities: BackupObjectStoreCapabilities = BackupObjectStoreCapabilities(
        atomicWholeObjectWrite = true,
        atomicReplace = false,
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
        val tempFile = File(parent, "${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tempFile).use { output ->
                output.withBufferedSink(write)
            }
            moveAtomically(
                source = tempFile,
                target = file,
                mode = mode,
            )
            return requireNotNull(stat(key)) {
                "Backup object '${key.value}' was not visible after writing."
            }
        } catch (e: FileAlreadyExistsException) {
            tempFile.delete()
            if (mode == BackupWriteMode.Create) {
                throw BackupObjectStoreException.AlreadyExists(
                    key = key,
                    cause = e,
                )
            }
            throw BackupObjectStoreException.Transient(
                operation = BackupObjectStoreOperation.Write,
                key = key,
                cause = e,
            )
        } catch (e: AccessDeniedException) {
            tempFile.delete()
            throw BackupObjectStoreException.PermissionDenied(
                operation = BackupObjectStoreOperation.Write,
                key = key,
                cause = e,
            )
        } catch (e: FileNotFoundException) {
            tempFile.delete()
            throw BackupObjectStoreException.PermissionDenied(
                operation = BackupObjectStoreOperation.Write,
                key = key,
                cause = e,
            )
        } catch (e: IOException) {
            tempFile.delete()
            throw BackupObjectStoreException.Transient(
                operation = BackupObjectStoreOperation.Write,
                key = key,
                cause = e,
            )
        } catch (e: SecurityException) {
            tempFile.delete()
            throw BackupObjectStoreException.PermissionDenied(
                operation = BackupObjectStoreOperation.Write,
                key = key,
                cause = e,
            )
        } catch (e: UnsupportedOperationException) {
            tempFile.delete()
            throw BackupObjectStoreException.Transient(
                operation = BackupObjectStoreOperation.Write,
                key = key,
                cause = e,
            )
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
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

    private fun moveAtomically(
        source: File,
        target: File,
        mode: BackupWriteMode,
    ) {
        atomicMove(source.toPath(), target.toPath(), mode)
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

private fun atomicMoveOptions(
    mode: BackupWriteMode,
): Array<CopyOption> = when (mode) {
    BackupWriteMode.Create -> arrayOf<CopyOption>(
        StandardCopyOption.ATOMIC_MOVE,
    )

    BackupWriteMode.CreateOrReplace -> arrayOf<CopyOption>(
        // Replacement with ATOMIC_MOVE is provider-specific; keep atomicReplace=false.
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

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
        return LocalFolderBackupObjectStore(
            root = repositoryPath.toBackupLocalPath(),
        )
    }

    private fun String.toBackupLocalPath(): LocalPath =
        toLocalPathFromFileUriOrNull()
            ?: LocalPath(this)
}
