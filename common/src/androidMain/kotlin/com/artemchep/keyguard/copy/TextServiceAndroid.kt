package com.artemchep.keyguard.copy

import android.app.Application
import android.content.Context
import android.net.Uri
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.InputStream
import androidx.core.net.toUri

class TextServiceAndroid(
    private val context: Context,
) : TextService {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
    )

    @OptIn(ExperimentalResourceApi::class)
    override suspend fun readFromResources(
        fileResource: FileResource,
    ): InputStream = Res.readBytes(fileResource.name)
        .inputStream()

    override fun readFromFile(uri: String): InputStream {
        val parsedUri = uri.toUri()
        return context.contentResolver.openInputStream(parsedUri)!!
    }
}
