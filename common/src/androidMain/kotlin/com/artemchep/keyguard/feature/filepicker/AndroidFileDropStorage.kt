package com.artemchep.keyguard.feature.filepicker

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import androidx.core.net.toUri

internal object AndroidFileDropStorage {
    private const val DIR_NAME = "keyguard_file_drops"
    private const val MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L

    fun copy(
        context: Context,
        sourceUri: Uri,
        displayName: String?,
    ): File {
        val target = createTargetFile(
            context = context,
            displayName = displayName,
        )
        var completed = false
        try {
            context.contentResolver
                .openInputStream(sourceUri)
                .use { input ->
                    requireNotNull(input) {
                        "Could not open dropped file."
                    }
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            completed = true
            return target
        } finally {
            if (!completed) {
                target.delete()
            }
        }
    }

    fun deleteIfManagedUri(
        context: Context,
        uri: String,
    ): Boolean {
        val file = managedFileOrNull(
            context = context,
            uri = uri,
        ) ?: return false
        return file.delete()
    }

    suspend fun cleanUpStale(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = MAX_AGE_MILLIS,
    ): Int = withContext(Dispatchers.IO) {
        val minLastModified = nowMillis - maxAgeMillis
        directory(context)
            .listFiles()
            .orEmpty()
            .count { file ->
                file.isFile &&
                        file.lastModified() <= minLastModified &&
                        file.delete()
            }
    }

    private fun createTargetFile(
        context: Context,
        displayName: String?,
    ): File {
        val dir = directory(context)
        dir.mkdirs()

        val name = sanitizeFileName(displayName)
        return dir.resolve("${System.currentTimeMillis()}-${UUID.randomUUID()}-$name")
    }

    private fun directory(
        context: Context,
    ): File = context.filesDir.resolve(DIR_NAME)

    private fun managedFileOrNull(
        context: Context,
        uri: String,
    ): File? {
        val parsedUri = runCatching {
            uri.toUri()
        }.getOrNull()
            ?.takeIf { it.scheme == "file" }
            ?: return null
        val path = parsedUri.path
            ?: return null

        val root = directory(context).canonicalFile
        val file = File(path).canonicalFile
        return file.takeIf {
            it.parentFile?.canonicalFile == root
        }
    }

    private fun sanitizeFileName(
        displayName: String?,
    ): String {
        val value = displayName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.map { char ->
                if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') {
                    char
                } else {
                    '_'
                }
            }
            ?.joinToString(separator = "")
            ?.trim('.', '_', '-')
            ?.take(80)
            .orEmpty()
        return value.ifBlank {
            "drop"
        }
    }
}
