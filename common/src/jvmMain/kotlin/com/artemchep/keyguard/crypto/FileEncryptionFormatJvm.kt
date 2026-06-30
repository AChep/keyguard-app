package com.artemchep.keyguard.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC_SHA256 = "HmacSHA256"

internal fun FileEncryptionFormat.createHmac(key: ByteArray): Mac =
    Mac.getInstance(HMAC_SHA256).apply {
        init(SecretKeySpec(key, HMAC_SHA256))
    }
