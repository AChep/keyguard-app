package com.artemchep.keyguard.apple.gpgtools

import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsOutputCoordinator
import com.artemchep.keyguard.feature.gpgagent.tools.result.GpgToolsResultRoute
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.create
import kotlin.uuid.Uuid

/** One operation's private files. Leases keep import/export/crypto IO alive after UI teardown. */
@OptIn(ExperimentalForeignApi::class)
internal class GpgToolsFiles(
    private val fileService: FileService,
    rootDirectory: String = root,
) : GpgToolsOutputCoordinator {
    data class File(val id: String, val uri: String, val name: String, val path: String)

    private val lock = NSLock()
    private val directory = "$rootDirectory/${Uuid.random()}"
    private val files = mutableMapOf<String, File>()
    private val discarded = mutableSetOf<String>()
    private var leases = 0
    private var closed = false

    init {
        check(NSFileManager.defaultManager.createDirectoryAtPath(
            directory, true, mapOf(NSFilePosixPermissions to PRIVATE_DIRECTORY_PERMISSIONS), null,
        )) { "Could not create GPG working directory." }
        NSURL.fileURLWithPath(directory).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
    }

    private fun <T> locked(block: () -> T): T {
        lock.lock()
        return try { block() } finally { lock.unlock() }
    }

    fun retain() = locked {
        check(!closed) { "GPG operation is no longer active." }
        leases++
    }

    fun release() = locked {
        check(leases > 0)
        leases--
        cleanUp()
    }

    fun create(name: String): File = locked {
        check(!closed)
        val id = Uuid.random().toString()
        // Never use names provided by a document or OpenPGP packet as a path component.
        var safeName = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != '\u007f' }.take(180)
            .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "gpg-output"
        // Filesystem component limits count encoded bytes, not Kotlin characters.
        while (safeName.encodeToByteArray().size > MAX_FILE_NAME_BYTES) safeName = safeName.dropLast(1)
        val folder = "$directory/$id"
        val path = "$folder/$safeName"
        val manager = NSFileManager.defaultManager
        check(
            manager.createDirectoryAtPath(
                folder,
                true,
                mapOf(NSFilePosixPermissions to PRIVATE_DIRECTORY_PERMISSIONS),
                null,
            ),
        )
        try {
            check(manager.createFileAtPath(path, null, mapOf(
                NSFilePosixPermissions to PRIVATE_FILE_PERMISSIONS,
                "NSFileProtectionKey" to "NSFileProtectionComplete",
            ))) { "Could not create GPG working file." }
            File(id, NSURL.fileURLWithPath(path).absoluteString!!, safeName, path).also { files[id] = it }
        } catch (e: Throwable) {
            manager.removeItemAtPath(folder, null)
            throw e
        }
    }

    fun get(id: String): File? = locked { files[id]?.takeUnless { id in discarded || closed } }

    fun find(uri: String): File? = locked { files.values.firstOrNull { it.uri == uri } }

    fun discard(id: String) = locked {
        if (id in files) discarded += id
        cleanUp()
    }

    fun discardUri(uri: String) = locked {
        files.values.firstOrNull { it.uri == uri }?.let { discarded += it.id }
        cleanUp()
    }

    fun close() = locked {
        closed = true
        cleanUp()
    }

    private fun cleanUp() {
        if (leases != 0) return
        if (closed) {
            NSFileManager.defaultManager.removeItemAtPath(directory, null)
            files.clear()
            discarded.clear()
        } else {
            discarded.forEach { id ->
                files.remove(id)?.let { file ->
                    NSFileManager.defaultManager.removeItemAtPath(file.path.substringBeforeLast('/'), null)
                }
            }
            discarded.clear()
        }
    }

    override suspend fun run(block: suspend () -> Unit) {
        retain()
        try {
            withContext(Dispatchers.Default) {
                currentCoroutineContext().ensureActive()
                block()
                currentCoroutineContext().ensureActive()
            }
        } finally { release() }
    }

    override suspend fun write(
        fileName: String,
        incognito: Boolean,
        block: suspend (uri: String) -> Unit,
    ): GpgToolsResultRoute.Args.FileOutput {
        retain()
        var file: File? = null
        try {
            file = create(fileName)
            block(file.uri)
            currentCoroutineContext().ensureActive()
            return GpgToolsResultRoute.Args.FileOutput(
                id = file.id, name = file.name,
                size = fileService.metadata(file.uri)?.size,
                incognito = incognito,
            )
        } catch (e: Throwable) {
            file?.let { discard(it.id) }
            throw e
        } finally { release() }
    }

    companion object {
        private val root: String by lazy {
            val path = NSTemporaryDirectory().trimEnd('/') + "/keyguard-gpg-v1"
            val manager = NSFileManager.defaultManager
            // Runs once per process. Only remove owned UUID directories, never arbitrary temp files.
            manager.contentsOfDirectoryAtPath(path, null)?.filterIsInstance<String>()?.forEach { name ->
                if (runCatching { Uuid.parse(name) }.isSuccess) {
                    val candidate = "$path/$name"
                    val attributes = manager.attributesOfItemAtPath(candidate, null)
                    if (attributes?.get(NSFileType) == NSFileTypeDirectory) {
                        manager.removeItemAtPath(candidate, null)
                    }
                }
            }
            check(manager.createDirectoryAtPath(
                path, true, mapOf(NSFilePosixPermissions to PRIVATE_DIRECTORY_PERMISSIONS), null,
            ))
            path
        }
    }
}

private const val PRIVATE_DIRECTORY_PERMISSIONS = 448

private const val MAX_FILE_NAME_BYTES = 240

private const val PRIVATE_FILE_PERMISSIONS = 384
