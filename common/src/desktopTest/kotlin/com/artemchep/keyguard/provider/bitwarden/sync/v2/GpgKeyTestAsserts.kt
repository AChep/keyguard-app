package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal fun assertGpgMetadataHasComponents(
    key: BitwardenCipher.GpgKey,
    min: Int = 2,
) {
    val metadata = assertNotNull(key.metadata, "GPG key metadata is missing")
    val components = metadata.certificates.single().components
    assertTrue(
        components.size >= min,
        "Expected at least $min certificate components, got ${components.size}",
    )
}
