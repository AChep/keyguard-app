package com.artemchep.keyguard.common.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MasterKdfTest {
    @Test
    fun `version 1 parameters are frozen`() {
        assertEquals(
            MasterKdf.Argon2id(
                iterations = 3,
                memoryKib = 65536,
                parallelism = 4,
            ),
            MasterKdf.Argon2id.V1,
        )
    }

    @Test
    fun `memory in bytes`() {
        assertEquals(64L * 1024 * 1024, MasterKdf.Argon2id.V1.memoryBytes)
    }

    @Test
    fun `the derived format has the version of its key derivation`() {
        assertEquals(MasterKdfVersion.V0, FingerprintPassword.Derived.versionOf(MasterKdf.Pbkdf2Legacy))
        assertEquals(MasterKdfVersion.V1, FingerprintPassword.Derived.versionOf(MasterKdf.Argon2id.V1))
        assertEquals(
            MasterKdfVersion.fromRaw(9),
            FingerprintPassword.Derived.versionOf(MasterKdf.Unsupported(MasterKdfVersion.fromRaw(9))),
        )
    }

    @Test
    fun `the wrapped format is version 2 whatever the key derivation`() {
        val salt = MasterPasswordSalt(byteArrayOf(1))
        val custom = MasterKdf.Argon2id(3, 65536, 2)
        assertEquals(MasterKdfVersion.V2, FingerprintPassword.Wrapped(custom, salt, byteArrayOf(2)).version)
        assertEquals(
            MasterKdfVersion.V2,
            FingerprintPassword.Wrapped(MasterKdf.Unsupported(MasterKdfVersion.V2), salt, byteArrayOf(2)).version,
        )
    }

    @Test
    fun `from version maps the versions that need no parameters`() {
        assertEquals(MasterKdf.Pbkdf2Legacy, MasterKdf.fromVersion(MasterKdfVersion.V0))
        assertEquals(MasterKdf.Argon2id.V1, MasterKdf.fromVersion(MasterKdfVersion.V1))
        assertEquals(
            MasterKdf.Unsupported(MasterKdfVersion.V2),
            MasterKdf.fromVersion(MasterKdfVersion.V2),
        )
        assertEquals(
            MasterKdf.Unsupported(MasterKdfVersion.fromRaw(999)),
            MasterKdf.fromVersion(MasterKdfVersion.fromRaw(999)),
        )
    }
}
