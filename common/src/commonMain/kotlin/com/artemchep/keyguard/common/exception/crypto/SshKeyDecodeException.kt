package com.artemchep.keyguard.common.exception.crypto

class SshKeyDecodeException(
    val header: String?,
    e: Throwable? = null,
) : RuntimeException("Failed to decode a private key with a header '${header.orEmpty()}'.", e)
