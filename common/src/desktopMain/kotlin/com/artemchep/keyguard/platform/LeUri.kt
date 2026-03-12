package com.artemchep.keyguard.platform

actual abstract class LeUri

data class LeUriImpl(
    val uri: String,
) : LeUri() {
    override fun toString(): String = uri
}

actual fun leParseUri(uri: String): LeUri = LeUriImpl(uri)
