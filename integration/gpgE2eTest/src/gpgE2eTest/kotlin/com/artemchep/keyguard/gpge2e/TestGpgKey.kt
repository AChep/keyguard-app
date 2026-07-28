package com.artemchep.keyguard.gpge2e

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey

/**
 * A single test key: its armored secret key (as exported by gpg) plus the per-(sub)key
 * metadata parsed from gpg's `--list-secret-keys --with-keygrip --with-colons` output.
 */
data class TestGpgKey(
    val name: String,
    val privateKeyArmored: String,
    val publicKeyArmored: String,
    /**
     * The primary key fingerprint, used as
     * the gpg recipient/local-user (`-r`/`-u`).
     */
    val primaryFingerprint: String,
    /**
     * One [GpgAgentKeyMetadataKey] per usable
     * (sub)key — both the primary and its subkeys.
     */
    val metadataKeys: List<GpgAgentKeyMetadataKey>,
)
