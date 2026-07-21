package com.google.crypto.tink.shaded.protobuf

import java.io.IOException

/**
 * Test stand-in for Tink's runtime-only shaded protobuf dependency, declared under
 * the exact name `tink-android` throws at runtime.
 */
class InvalidProtocolBufferException(
    message: String,
) : IOException(message)
