package com.artemchep.keyguard.copy

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.dirs.DirsService
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.OutputStream

class DirsServiceAndroid(
    private val context: Context,
) : DirsService {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
    )

    override fun saveToDownloads(
        fileName: String,
        write: suspend (OutputStream) -> Unit,
    ): IO<Unit> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveToDownloadsApi29(
            fileName = fileName,
            write = write,
        )
    } else {
        saveToDownloadsApi26(
            fileName = fileName,
            write = write,
        )
    }

    private fun saveToDownloadsApi26(
        fileName: String,
        write: suspend (OutputStream) -> Unit,
    ) = ioEffect {
        val downloadsDir = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = downloadsDir.resolve(fileName)
        // Ensure the parent directory does exist
        // before writing the file.
        file.parentFile?.mkdirs()
        file.outputStream()
            .use {
                write(it)
            }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloadsApi29(
        fileName: String,
        write: suspend (OutputStream) -> Unit,
    ) = ioEffect {
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(fileName.substringAfterLast('.'))
        ah(
            write = write,
        ) {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            if (mimeType != null) {
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            }
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)

            // Save to external downloads
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }.bind()
        Unit
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ah(
        write: suspend (OutputStream) -> Unit,
        configure: ContentValues.() -> Uri,
    ) = ioEffect {
        val contentResolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, true)
        }
        val contentUri = values.run(configure)
        val fileUri = contentResolver.insert(contentUri, values)
        requireNotNull(fileUri)

        try {
            // Try to save the file into the
            // give directory.
            val os = contentResolver
                .openOutputStream(fileUri)
            requireNotNull(os) {
                "File output stream is null."
            }

            println("Before export")
            os.use { outputStream ->
                write(outputStream)
            }

            // Update the record, stating that we have completed
            // saving the file.
            values.put(MediaStore.MediaColumns.IS_PENDING, false)
            contentResolver.update(fileUri, values, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
            contentResolver.delete(fileUri, null, null)
        }
    }

    // Checks if a volume containing external storage is available
    // for read and write.
    private fun isExternalStorageWritable() =
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

}
