package com.artemchep.keyguard.test

import com.artemchep.keyguard.common.service.text.Base64Service

/**
 * A [Base64Service] that returns its input unchanged.
 */
internal object IdentityBase64Service : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}
