package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfAccountMapper
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A mapper that always raises. `CxfSecretMapper` absorbs whatever the injected
 * SSH exporter throws, so the guard at the service boundary is only reachable
 * through a double at this level.
 */
private class RaisingAccountMapper(
    private val error: Throwable,
) : CxfAccountMapper {
    override fun buildAccountResult(
        profile: DProfile,
        ciphers: List<DSecret>,
        allowedTypes: Set<CxfCredentialType>,
        folders: List<DFolder>,
    ): CxfAccountResult = throw error
}

/**
 * `CxfExportService.buildAccountResult` promises to be total: mapping one
 * account may lose that account, but it may never take the call — and with it
 * every *other* account of the vault — down with it. The mirror of
 * `CxfImportServiceImpl.parse`'s contract on the other direction.
 */
class CxfExportServiceTotalityTest {
    private fun serviceRaising(error: Throwable) =
        CxfExportServiceImpl(mapper = RaisingAccountMapper(error))

    private fun CxfExportService.build() = buildAccountResult(
        profile = cxfProfile(),
        ciphers = listOf(cxfLoginSecret(login = DSecret.Login(password = "s3cr3t"))),
        allowedTypes = CxfCredentialType.ALL,
    )

    @Test
    fun `a mapper that throws yields a counted account failure, not a throw`() {
        val result = serviceRaising(IllegalStateException("mapper is broken")).build()
        assertNull(result.account)
        assertEquals(1, result.skips[CxfExportSkipReason.Account])
        // Load-bearing: `buildProfileAccounts` drops a profile whose account is
        // null and whose `totalCount` is zero, so a count that did not reach the
        // total would be erased by the filter meant to preserve it.
        assertEquals(1, result.skips.totalCount)
    }

    @Test
    fun `the totality boundary still lets cancellation through`() {
        assertFailsWith<CancellationException> {
            serviceRaising(CancellationException("cancelled")).build()
        }
    }

    @Test
    fun `the totality boundary still lets a fatal error through`() {
        // A blown stack from a pathological folder chain is the process
        // breaking, not a document being hostile: the export input is the
        // user's own vault, so this must not be absorbed.
        assertFailsWith<StackOverflowError> {
            serviceRaising(StackOverflowError("deep")).build()
        }
    }

    @Test
    fun `the account reason is never raised by the mapper itself`() {
        // `CxfSecretMapper.isUnrepresentable` reads a non-zero per-item skip
        // total as "a credential was already counted", so an account-level
        // reason inside that tally would suppress a real item count. The reason
        // exists only above the mapper.
        val healthy = CxfExportServiceImpl(
            sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(error = IllegalStateException("gone")),
        ).buildAccountResult(
            profile = cxfProfile(),
            ciphers = listOf(
                cxfSecret(
                    type = DSecret.Type.SshKey,
                    sshKey = DSecret.SshKey(privateKey = "pem", publicKey = "ssh-ed25519 AAAA"),
                ),
                cxfSecret(type = DSecret.Type.GpgKey),
            ),
            allowedTypes = CxfCredentialType.ALL,
        )
        assertEquals(0, healthy.skips[CxfExportSkipReason.Account])
        assertEquals(
            cxfExportSkips(
                CxfExportSkipReason.SshKey to 1,
                CxfExportSkipReason.Item to 1,
            ),
            healthy.skips,
        )
    }
}
