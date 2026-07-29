package com.artemchep.keyguard.platform

import com.artemchep.keyguard.util.io.toByteArray
import com.artemchep.keyguard.util.io.toNSData
import platform.Foundation.NSData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
internal fun NSData.toSecurityScopedBookmarkToken(): String =
    Base64.Default.encode(toByteArray())

@OptIn(ExperimentalEncodingApi::class)
internal fun String.toSecurityScopedBookmarkDataOrNull(): NSData? = runCatching {
    Base64.Default.decode(this)
        .toNSData()
}.getOrNull()
