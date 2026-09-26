package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.text.Base64Service
import org.apache.commons.codec.binary.Base64

class Base64ServiceJvm : Base64Service {
    private val delegate = Base64()

    override fun encode(bytes: ByteArray): ByteArray = delegate.encode(bytes)

    override fun decode(bytes: ByteArray): ByteArray = delegate.decode(bytes)
}
