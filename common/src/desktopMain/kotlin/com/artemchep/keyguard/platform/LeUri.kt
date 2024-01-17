package com.artemchep.keyguard.platform

import java.io.File

actual abstract class LeUri

data class LeUriImpl(
    val uri: String,
) : LeUri() {
    override fun toString(): String = uri
}

actual fun leParseUri(uri: String): LeUri = LeUriImpl(uri)

actual fun leParseUri(file: File): LeUri = LeUriImpl(file.toURI().toString())
