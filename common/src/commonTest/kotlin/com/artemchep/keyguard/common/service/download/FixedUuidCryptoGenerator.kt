package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.test.TestCryptoGenerator

/**
 * Returns the given [uuids] in order, then falls back to `download-<n>`.
 */
internal class FixedUuidCryptoGenerator(
    private vararg val uuids: String,
) : TestCryptoGenerator() {
    private var uuidIndex = 0

    override fun uuid(): String =
        uuids.getOrElse(uuidIndex++) { "download-$uuidIndex" }
}
