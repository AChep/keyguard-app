package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.text.Base32Service
import org.apache.commons.codec.binary.Base32

class Base32ServiceJvm : Base32Service {
    private val delegate = Base32()

    override fun encode(bytes: ByteArray): ByteArray = delegate.encode(bytes)

    override fun decode(bytes: ByteArray): ByteArray = delegate.decode(bytes)
}
