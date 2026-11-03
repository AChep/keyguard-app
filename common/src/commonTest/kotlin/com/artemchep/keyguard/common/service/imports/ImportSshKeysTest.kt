package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.imports.mapping.importedSshKeyOrNull
import com.artemchep.keyguard.test.FakeSshKeyImportService
import com.artemchep.keyguard.test.fakeSshKeyPair
import com.artemchep.keyguard.test.sshKeyImportFailure
import com.artemchep.keyguard.test.sshKeyImportNeedsPassphrase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImportSshKeysTest {
    @Test
    fun `an encrypted key is retried with the passphrase`() {
        val service = FakeSshKeyImportService(
            results = listOf(
                sshKeyImportNeedsPassphrase(),
                SshKeyImportResult.Success(fakeSshKeyPair()),
            ),
        )
        val key = importedSshKeyOrNull(KEY, service, passphrase = " secret ")
        assertNotNull(key)
        assertEquals(listOf(null, " secret "), service.requests.map { it.passphrase })
    }

    @Test
    fun `an unencrypted key is read without the passphrase`() {
        val service = FakeSshKeyImportService()
        val key = importedSshKeyOrNull(KEY, service, passphrase = "stale")
        assertNotNull(key)
        assertEquals(listOf(null), service.requests.map { it.passphrase })
    }

    @Test
    fun `an encrypted key without a passphrase is not retried`() {
        for (passphrase in listOf(null, "")) {
            val service = FakeSshKeyImportService(result = sshKeyImportNeedsPassphrase())
            assertNull(importedSshKeyOrNull(KEY, service, passphrase = passphrase))
            assertEquals(1, service.callCount)
        }
    }

    @Test
    fun `a key that fails to read is not retried`() {
        val service = FakeSshKeyImportService(result = sshKeyImportFailure())
        assertNull(importedSshKeyOrNull(KEY, service, passphrase = "secret"))
        assertEquals(1, service.callCount)
    }

    @Test
    fun `a wrong passphrase yields no key`() {
        val service = FakeSshKeyImportService(
            results = listOf(sshKeyImportNeedsPassphrase()),
            result = sshKeyImportFailure(),
        )
        assertNull(importedSshKeyOrNull(KEY, service, passphrase = "wrong"))
        assertEquals(2, service.callCount)
    }

    @Test
    fun `a failing backend yields no key`() {
        val service = FakeSshKeyImportService(error = IllegalStateException("Unavailable backend"))
        assertNull(importedSshKeyOrNull(KEY, service, passphrase = "secret"))
        assertEquals(1, service.callCount)
    }

    private companion object {
        const val KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nkey\n-----END OPENSSH PRIVATE KEY-----\n"
    }
}
