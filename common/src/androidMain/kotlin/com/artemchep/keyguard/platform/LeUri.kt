package com.artemchep.keyguard.platform

import android.net.Uri
import android.os.Parcelable
import androidx.core.net.toUri
import kotlinx.parcelize.Parcelize

actual abstract class LeUri : Parcelable

@Parcelize
data class LeUriImpl(
    private val rawUri: String,
) : LeUri() {
    constructor(uri: Uri) : this(uri.toString())

    val uri: Uri
        get() = rawUri.toUri()

    override fun toString(): String = rawUri
}

actual fun leParseUri(uri: String): LeUri = LeUriImpl(uri)
