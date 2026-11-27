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
    fun `version follows the parameters`() {
        assertEquals(MasterKdfVersion.V0, MasterKdf.Pbkdf2Legacy.version)
        assertEquals(MasterKdfVersion.V1, MasterKdf.Argon2id.V1.version)
        assertEquals(MasterKdfVersion.V1, MasterKdf.Argon2id(3, 65536, 4).version)
        assertEquals(MasterKdfVersion.V2, MasterKdf.Argon2id(3, 65536, 2).version)
        assertEquals(MasterKdfVersion.V2, MasterKdf.Argon2id(4, 65536, 4).version)
        assertEquals(MasterKdfVersion.V2, MasterKdf.Argon2id(3, 131072, 4).version)
        assertEquals(
            MasterKdfVersion.fromRaw(9),
            MasterKdf.Unsupported(MasterKdfVersion.fromRaw(9)).version,
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
