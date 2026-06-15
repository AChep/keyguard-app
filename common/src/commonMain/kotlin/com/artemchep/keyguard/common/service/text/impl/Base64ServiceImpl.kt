package com.artemchep.keyguard.common.service.text.impl

import com.artemchep.keyguard.common.service.text.Base64Service
import kotlin.io.encoding.Base64

// TODO: Verify that the implementation matches the Apache Base64 implementation, which
//  it doesn't from the quick glance. It seems like the decode function is much less strict
//  on the Apache side. We should fix that before we can truly use the KMP implementation.
class Base64ServiceImpl : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = Base64.encodeToByteArray(bytes)

    override fun decode(bytes: ByteArray): ByteArray = Base64.decode(bytes)
}
