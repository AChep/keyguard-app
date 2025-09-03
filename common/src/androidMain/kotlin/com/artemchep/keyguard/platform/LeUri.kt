package com.artemchep.keyguard.platform

import android.net.Uri
import android.os.Parcelable
import androidx.core.net.toUri
import kotlinx.parcelize.Parcelize
import java.io.File

actual abstract class LeUri : Parcelable

@Parcelize
data class LeUriImpl(
    val uri: Uri,
) : LeUri() {
    override fun toString(): String = uri.toString()
}

actual fun leParseUri(uri: String): LeUri = uri.toUri().let(::LeUriImpl)

actual fun leParseUri(file: File): LeUri = file.toUri().let(::LeUriImpl)
