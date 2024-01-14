package com.artemchep.keyguard.copy

import android.app.Application
import android.content.Context
import android.net.Uri
import com.artemchep.keyguard.common.service.text.TextService
import dev.icerock.moko.resources.FileResource
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.InputStream

class TextServiceAndroid(
    private val context: Context,
) : TextService {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
    )

    override fun readFromResources(
        fileResource: FileResource,
    ): InputStream = fileResource.inputStream()

    private fun FileResource.inputStream() = context.resources
        .openRawResource(rawResId)

    override fun readFromFile(uri: String): InputStream {
        val parsedUri = Uri.parse(uri)
        return context.contentResolver.openInputStream(parsedUri)!!
    }
}
