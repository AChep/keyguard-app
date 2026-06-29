package com.artemchep.keyguard.common.service.file

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.time.Instant

class FileServiceImpl : FileService {
    override fun exists(
        uri: String,
    ): Boolean = SystemFileSystem
        .exists(
            path = uri
                .toFilePath(action = FileAccessAction.Read),
        )

    override fun metadata(
        uri: String,
        accessToken: FileAccessToken?,
    ): FileMetadata? = runCatching {
        val localPath = uri.toLocalPathFromFileUriOrNull()
            ?: return@runCatching null
        val metadata = SystemFileSystem.metadataOrNull(
            path = Path(localPath.value),
        ) ?: return@runCatching null
        FileMetadata(
            lastModified = fileLastModifiedMillis(localPath.value)
                ?.let(Instant::fromEpochMilliseconds),
            size = metadata.size,
        )
    }.getOrNull()

    override fun readFromFile(
        uri: String,
    ): Source = SystemFileSystem
        .source(
            path = uri
                .toFilePath(action = FileAccessAction.Read),
        )
        .buffered()

    override fun writeToFile(
        uri: String,
    ): Sink = SystemFileSystem
        .sink(
            path = uri
                .toFilePath(action = FileAccessAction.Write),
        )
        .buffered()

    override fun atomicWriteToFile(
        uri: String,
        accessToken: FileAccessToken?,
        bytes: ByteArray,
    ): Boolean {
        val tempUri = atomicTempSiblingUriOrNull(uri) ?: return false
        var moved = false
        return try {
            writeToFile(tempUri)
                .use { sink ->
                    sink.write(bytes)
                    sink.flush()
                }
            moved = atomicMove(
                sourceUri = tempUri,
                destinationUri = uri,
                accessToken = accessToken,
            )
            moved
        } finally {
            if (!moved) {
                delete(tempUri)
            }
        }
    }

    override fun delete(uri: String): Boolean = runCatching {
        SystemFileSystem
            .delete(
                path = uri
                    .toFilePath(action = FileAccessAction.Write),
                mustExist = false,
            )
        true
    }.getOrDefault(false)

    override fun atomicMove(
        sourceUri: String,
        destinationUri: String,
        accessToken: FileAccessToken?,
    ): Boolean = runCatching {
        val source = sourceUri.toLocalPathFromFileUriOrNull()
            ?: return false
        val destination = destinationUri.toLocalPathFromFileUriOrNull()
            ?: return false
        SystemFileSystem.atomicMove(
            source = Path(source.value),
            destination = Path(destination.value),
        )
        true
    }.getOrDefault(false)
}

private enum class FileAccessAction(
    val errorVerb: String,
) {
    Read(errorVerb = "read from"),
    Write(errorVerb = "write to"),
}

private fun String.toFilePath(action: FileAccessAction): Path =
    toLocalPathFromFileUriOrNull()
        ?.let { Path(it.value) }
        ?: run {
            val msg = "Unsupported URI protocol, could not ${action.errorVerb} '$this'."
            throw IllegalStateException(msg)
        }

private fun atomicTempSiblingUriOrNull(uri: String): String? {
    // content:// (SAF) documents cannot be renamed onto.
    if (uri.startsWith("content:")) return null
    // A query/fragment would make the appended suffix part of the query
    // rather than a sibling path; bail so the caller writes directly instead.
    if (uri.contains('?') || uri.contains('#')) return null
    val nonce = Random.nextLong().toULong().toString(16)
    return "$uri.$nonce.kgtmp"
}
