package com.artemchep.keyguard.common.service.file

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

interface FileService {
    fun exists(uri: String): Boolean

    fun readFromFile(uri: String): Source

    fun writeToFile(uri: String): Sink

    fun delete(uri: String): Boolean

    fun deleteManagedSourceFile(uri: String): Boolean = false
}

class PureFileService : FileService {
    override fun exists(uri: String): Boolean =
        SystemFileSystem
            .exists(
                path = uri
                    .toFilePath(action = FileAccessAction.Read),
            )

    override fun readFromFile(uri: String): Source =
        SystemFileSystem
            .source(
                path = uri
                    .toFilePath(action = FileAccessAction.Read),
            )
            .buffered()

    override fun writeToFile(uri: String): Sink =
        SystemFileSystem
            .sink(
                path = uri
                    .toFilePath(action = FileAccessAction.Write),
            )
            .buffered()

    override fun delete(uri: String): Boolean = runCatching {
        SystemFileSystem
            .delete(
                path = uri
                    .toFilePath(action = FileAccessAction.Write),
                mustExist = false,
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
