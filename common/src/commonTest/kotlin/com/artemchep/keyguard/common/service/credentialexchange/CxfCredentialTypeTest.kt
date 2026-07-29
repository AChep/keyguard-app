package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CxfCredentialTypeTest {
    @Test
    fun `parse matches serial names case-insensitively`() {
        assertEquals(CxfCredentialType.BasicAuth, CxfCredentialType.parse("basic-auth"))
        assertEquals(CxfCredentialType.SshKey, CxfCredentialType.parse(" SSH-KEY "))
    }

    @Test
    fun `parse accepts the webauthn public-key alias for passkeys`() {
        assertEquals(CxfCredentialType.Passkey, CxfCredentialType.parse("public-key"))
    }

    @Test
    fun `parse returns null for unknown values`() {
        assertNull(CxfCredentialType.parse("x509-certificate"))
    }

    @Test
    fun `parseAll ignores unknown values instead of widening the filter`() {
        // CXP §3.2: the exporting provider MUST ignore unknown requested type
        // values. When nothing is recognized the result is an empty filter
        // (export nothing) — the "all types" semantics of an absent list must
        // be requested explicitly through CxfCredentialType.ALL.
        assertEquals(
            setOf(CxfCredentialType.Totp),
            CxfCredentialType.parseAll(listOf("totp", "x509-certificate")),
        )
        assertTrue(CxfCredentialType.parseAll(listOf("x509-certificate")).isEmpty())
    }
}
