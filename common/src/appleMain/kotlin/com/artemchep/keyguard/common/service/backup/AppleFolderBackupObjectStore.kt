package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.backup.ffi.keyguard_backup_openat
import com.artemchep.keyguard.crypto.createPrivateTemporaryStorage
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.atomic.writeFileAtomically
import com.artemchep.keyguard.util.io.scratch.PrivateTemporarySpillStorage
import com.artemchep.keyguard.util.io.scratch.PrivateTemporaryStorage
import com.artemchep.keyguard.util.io.spool.buildSnapshot
import com.artemchep.keyguard.util.io.spool.copyTo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import platform.posix.AT_SYMLINK_NOFOLLOW
import platform.posix.EACCES
import platform.posix.EINTR
import platform.posix.ELOOP
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.EPERM
import platform.posix.EROFS
import platform.posix.O_CLOEXEC
import platform.posix.O_DIRECTORY
import platform.posix.O_NOFOLLOW
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.SEEK_SET
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.closedir
import platform.posix.dup
import platform.posix.errno
import platform.posix.fdopendir
import platform.posix.fstat
import platform.posix.fstatat
import platform.posix.lseek
import platform.posix.open
import platform.posix.readdir
import platform.posix.set_posix_errno
import platform.posix.stat
import platform.posix.unlinkat
import kotlin.coroutines.CoroutineContext

/**
 * Coordinates provider access for each operation. Private, pathless scratch files allow
 * suspending writers and returned readers to outlive a synchronous Foundation accessor
 * without retaining provider locks or buffering an attachment in memory.
 */
@OptIn(ExperimentalForeignApi::class, InternalKeyguardIoApi::class)
class AppleFolderBackupObjectStore internal constructor(
    private val access: AppleBackupFolderAccess,
) : BackupObjectStore {
    override val capabilities = BackupObjectStoreCapabilities(
        atomicWholeObjectWrite = true,
        atomicReplace = true,
        rangeRead = true,
        strongReadAfterWrite = true,
        strongListAfterWrite = true,
    )

    override suspend fun stat(key: BackupObjectKey): BackupObjectInfo? = withContext(Dispatchers.IO) {
        withObjectParent(key, BackupObjectStoreOperation.Stat) { _, parent, name ->
            metadata(parent, name, key, BackupObjectStoreOperation.Stat)
        }
    }

    override suspend fun read(key: BackupObjectKey, range: BackupByteRange?): Source {
        var acquired: PrivateTemporaryStorage? = null
        try {
            return withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val storage = createPrivateTemporaryStorage()
                acquired = storage
                storage.sink().buffered().use { output ->
                    withObjectParent(key, BackupObjectStoreOperation.Read) { root, parent, name ->
                        if (metadata(parent, name, key, BackupObjectStoreOperation.Read) == null) {
                            throw BackupObjectStoreException.NotFound(key)
                        }
                        coordinateObjectRead(root, key) {
                            copyObjectTo(output, parent, name, key, range, context)
                        }
                    } ?: throw BackupObjectStoreException.NotFound(key)
                }
                storage.sealForReading()
                val source = storage.source()
                object : RawSource by source {
                    override fun close() {
                        try { source.close() } finally { storage.close() }
                    }
                }.buffered()
            }
        } catch (e: Throwable) {
            // withContext may discard a completed result when its caller was cancelled.
            acquired?.close()
            throw e
        }
    }

    override suspend fun write(
        key: BackupObjectKey,
        mode: BackupWriteMode,
        write: suspend (Sink) -> Unit,
    ): BackupObjectInfo = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        try {
            PrivateTemporarySpillStorage(createPrivateTemporaryStorage())
                .buildSnapshot { write(it) }
                .use { snapshot ->
                    context.ensureActive()
                    access.coordinate(BackupObjectStoreOperation.Write, key) { path ->
                        val result = writeFileAtomically(
                            AtomicFileDestination(LocalPath(path), AtomicRelativePath.parse(key.value)),
                            mode.toBackupAtomicWriteOptions(),
                            checkCancellation = { context.ensureActive() },
                        ) { sink ->
                            snapshot.copyTo(sink) { context.ensureActive() }
                            snapshot.size
                        }
                        BackupObjectInfo(key, result.value, null, result.receipt).requireAtomicCleanupComplete()
                    }
                }
        } catch (e: Exception) {
            throw atomicWriteFailureOrNull(key, e) ?: e
        }
    }

    override suspend fun list(
        prefix: BackupObjectKeyPrefix,
        cursor: BackupListCursor?,
    ): BackupObjectListPage = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        access.coordinate(BackupObjectStoreOperation.List) { path ->
            withRoot(path, BackupObjectStoreOperation.List) { root ->
                val items = mutableListOf<BackupObjectInfo>()
                fun visit(fd: Int, parent: String) {
                    context.ensureActive()
                    val duplicate = dup(fd)
                    if (duplicate < 0) fail(BackupObjectStoreOperation.List)
                    val dir = fdopendir(duplicate)
                    if (dir == null) {
                        platform.posix.close(duplicate)
                        fail(BackupObjectStoreOperation.List)
                    }
                    try {
                        while (true) {
                            context.ensureActive()
                            set_posix_errno(0)
                            val entry = readdir(dir)
                            if (entry == null) {
                                if (errno != 0) fail(BackupObjectStoreOperation.List)
                                break
                            }
                            val name = entry.pointed.d_name.toKString()
                            if (name == "." || name == "..") continue
                            val value = parent + name
                            // Skip entries that can neither match nor contain a matching key.
                            if (!value.startsWith(prefix.value) && !prefix.value.startsWith("$value/")) continue
                            // A repository may coexist with unrelated files with non-portable names.
                            val key = runCatching { BackupObjectKey(value) }.getOrNull() ?: continue
                            val (type, size) = statAt(fd, name, key, BackupObjectStoreOperation.List) ?: continue
                            if (type == S_IFDIR) {
                                val child = keyguard_backup_openat(
                                    fd,
                                    name,
                                    O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC,
                                )
                                if (child < 0) fail(BackupObjectStoreOperation.List, key)
                                try { visit(child, "$value/") } finally { platform.posix.close(child) }
                            } else if (type == S_IFREG && value.startsWith(prefix.value)) {
                                items += BackupObjectInfo(key, size, null)
                            }
                        }
                    } finally { closedir(dir) }
                }
                visit(root, "")
                BackupObjectListPage(items.sortedBy { it.key.value })
            }
        }
    }

    override suspend fun delete(key: BackupObjectKey): Unit = withContext(Dispatchers.IO) {
        withObjectParent(key, BackupObjectStoreOperation.Delete) { _, parent, name ->
            // Unlink the entry itself, never its target; directories are not backup objects.
            if (
                metadata(parent, name, key, BackupObjectStoreOperation.Delete) != null &&
                unlinkat(parent, name, 0) != 0 && errno != ENOENT
            ) {
                fail(BackupObjectStoreOperation.Delete, key)
            }
        }
    }

    override suspend fun close() = access.close()

    private fun copyObjectTo(
        output: Sink,
        parent: Int,
        name: String,
        key: BackupObjectKey,
        range: BackupByteRange?,
        context: CoroutineContext,
    ) {
        val fd = keyguard_backup_openat(parent, name, O_RDONLY or O_NOFOLLOW or O_NONBLOCK or O_CLOEXEC)
        if (fd < 0) fail(BackupObjectStoreOperation.Read, key)
        try {
            val size = memScoped {
                val info = alloc<stat>()
                if (fstat(fd, info.ptr) != 0) fail(BackupObjectStoreOperation.Read, key)
                if (info.st_mode.toInt() and S_IFMT != S_IFREG) {
                    throw BackupObjectStoreException.NotFound(key)
                }
                info.st_size
            }
            if (range != null && (range.offset > size || (range.length ?: 0L) > size - range.offset)) {
                throw BackupObjectStoreException.InvalidRange(key, range)
            }
            val offset = range?.offset ?: 0L
            if (lseek(fd, offset, SEEK_SET) < 0) fail(BackupObjectStoreOperation.Read, key)
            var remaining = range?.length ?: (size - offset)
            val bytes = ByteArray(64 * 1024)
            while (remaining > 0L) {
                context.ensureActive()
                val count = platform.posix.read(fd, bytes.refTo(0), minOf(bytes.size.toLong(), remaining).toULong())
                if (count < 0) {
                    if (errno == EINTR) continue
                    fail(BackupObjectStoreOperation.Read, key)
                }
                if (count == 0L) throw BackupObjectStoreException.Transient(BackupObjectStoreOperation.Read, key)
                output.write(bytes, 0, count.toInt())
                remaining -= count
            }
        } finally {
            platform.posix.close(fd)
        }
    }

    /** Returns the entry's `S_IFMT` type and size without following it, or `null` when absent. */
    private fun statAt(
        parent: Int,
        name: String,
        key: BackupObjectKey,
        operation: BackupObjectStoreOperation,
    ): Pair<Int, Long>? = memScoped {
        val info = alloc<stat>()
        if (fstatat(parent, name, info.ptr, AT_SYMLINK_NOFOLLOW) != 0) {
            if (errno == ENOENT) return@memScoped null
            fail(operation, key)
        }
        (info.st_mode.toInt() and S_IFMT) to info.st_size
    }

    private fun metadata(
        parent: Int,
        name: String,
        key: BackupObjectKey,
        operation: BackupObjectStoreOperation,
    ): BackupObjectInfo? = statAt(parent, name, key, operation)
        ?.takeIf { (type) -> type == S_IFREG }
        ?.let { (_, size) -> BackupObjectInfo(key, size, null) }

    /** Runs [block] with the retained parent of [key], or returns `null` when a parent is absent. */
    private suspend fun <T> withObjectParent(
        key: BackupObjectKey,
        operation: BackupObjectStoreOperation,
        block: AppleBackupFolderAccess.Coordination.(root: String, parent: Int, name: String) -> T,
    ): T? = access.coordinate(operation, key) { path ->
        withRoot(path, operation) { root ->
            withParent(root, key, operation) { parent, name -> block(path, parent, name) }
        }
    }

    private fun <T> withRoot(path: String, operation: BackupObjectStoreOperation, block: (Int) -> T): T {
        val root = open(path, O_RDONLY or O_DIRECTORY or O_CLOEXEC)
        if (root < 0) fail(operation)
        try { return block(root) } finally { platform.posix.close(root) }
    }

    private fun <T> withParent(
        root: Int,
        key: BackupObjectKey,
        operation: BackupObjectStoreOperation,
        block: (Int, String) -> T,
    ): T? {
        // Apply the atomic write rules, which also reject NUL and drive-like components.
        val parts = AtomicRelativePath.parse(key.value).value.split('/')
        var parent = dup(root)
        if (parent < 0) fail(operation, key)
        try {
            for (part in parts.dropLast(1)) {
                val child = keyguard_backup_openat(parent, part, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
                if (child < 0) {
                    if (errno == ENOENT) return null
                    fail(operation, key)
                }
                platform.posix.close(parent)
                parent = child
            }
            return block(parent, parts.last())
        } finally { platform.posix.close(parent) }
    }

    private fun fail(
        operation: BackupObjectStoreOperation,
        key: BackupObjectKey? = null,
    ): Nothing = throw when (errno) {
        ENOENT -> key?.let { BackupObjectStoreException.NotFound(it, operation) }
            ?: BackupObjectStoreException.PermissionDenied(operation)
        EACCES, EPERM, EROFS, ELOOP, ENOTDIR -> BackupObjectStoreException.PermissionDenied(operation, key)
        else -> BackupObjectStoreException.Transient(operation, key)
    }
}

class AppleFolderBackupObjectStoreFactory(
    private val onAccessRefreshed: suspend (BackupStoreConfig.Local, BackupStoreConfig.Local) -> Unit = { _, _ -> },
) : BackupObjectStoreFactory {
    override suspend fun open(store: BackupStoreConfig): BackupObjectStore {
        var acquired: AppleBackupFolderAccess? = null
        try {
            return withContext(Dispatchers.IO) {
                require(store is BackupStoreConfig.Local)
                val access = AppleBackupFolderAccess.open(store)
                acquired = access
                access.refreshedConfig?.let { onAccessRefreshed(store, it) }
                AppleFolderBackupObjectStore(access)
            }
        } catch (e: Throwable) {
            acquired?.close()
            throw e
        }
    }
}
