package com.artemchep.keyguard.crypto

internal fun throwLegacyAesUnsupported(): Nothing {
    throw IllegalArgumentException(
        "The support for AES CBC 256 (enc-type 0) is not longer provided! " +
                "Please upgrade your vault to migrate to a newer encryption type!",
    )
}
