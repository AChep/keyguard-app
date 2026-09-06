package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@Suppress("FunctionNaming")
class CipherConflictResolverTest {
    @Test
    fun `three-way merge keeps independent local and remote edits`() {
        val base = cipher()
        val local = base.copy(
            name = "Local name",
            revisionDate = LOCAL_REVISION,
        )
        val remote = base.copy(
            notes = "Remote notes",
            revisionDate = REMOTE_REVISION,
        )

        val resolution = resolveCipherConflict(
            base = base,
            local = local,
            remote = remote,
            at = MERGE_REVISION,
            preserveDisplacedSecretsInPasswordHistory = true,
            gpgCertificateMaterialReconciler = UnexpectedGpgReconciler,
            gpgKeyMetadataResolver = null,
        )

        assertEquals(CipherConflictResolution.Mode.ThreeWay, resolution.mode)
        assertTrue(resolution.requiresRemoteWrite)
        assertEquals("Local name", resolution.cipher.name)
        assertEquals("Remote notes", resolution.cipher.notes)
        assertEquals(MERGE_REVISION, resolution.cipher.revisionDate)
    }

    @Test
    fun `three-way merge uses remote value when both sides edit one scalar`() {
        val base = cipher()

        val resolution = resolveCipherConflict(
            base = base,
            local = base.copy(name = "Local name"),
            remote = base.copy(name = "Remote name"),
            at = MERGE_REVISION,
            preserveDisplacedSecretsInPasswordHistory = true,
            gpgCertificateMaterialReconciler = UnexpectedGpgReconciler,
            gpgKeyMetadataResolver = null,
        )

        assertEquals("Remote name", resolution.cipher.name)
    }

    @Test
    fun `fallback preserves a losing local password and requests a write`() {
        val local = cipher(password = "local-password")
        val remote = cipher(password = "remote-password")

        val resolution = resolveCipherConflict(
            base = null,
            local = local,
            remote = remote,
            at = MERGE_REVISION,
            preserveDisplacedSecretsInPasswordHistory = true,
            gpgCertificateMaterialReconciler = UnexpectedGpgReconciler,
            gpgKeyMetadataResolver = null,
        )

        assertEquals(CipherConflictResolution.Mode.RemoteFallback, resolution.mode)
        assertTrue(resolution.requiresRemoteWrite)
        assertEquals("remote-password", resolution.cipher.login?.password)
        assertEquals(
            listOf("local-password"),
            resolution.cipher.passwordHistory.map { it.password },
        )
    }

    @Test
    fun `fallback adopts remote without a write when nothing needs preservation`() {
        val remote = cipher(password = null)

        val resolution = resolveCipherConflict(
            base = null,
            local = remote,
            remote = remote,
            at = MERGE_REVISION,
            preserveDisplacedSecretsInPasswordHistory = true,
            gpgCertificateMaterialReconciler = UnexpectedGpgReconciler,
            gpgKeyMetadataResolver = null,
        )

        assertFalse(resolution.requiresRemoteWrite)
        assertNull(resolution.cipher.login?.password)
    }

    @Test
    fun `fallback does not preserve displaced secrets when history recovery is disabled`() {
        val localBase = cipher(password = "local-password")
        val local = localBase.copy(
            login = localBase.login?.copy(
                totp = "local-totp",
            ),
            fields = listOf(
                BitwardenCipher.Field(
                    name = "API token",
                    value = "local-token",
                    type = BitwardenCipher.Field.Type.Hidden,
                ),
            ),
        )
        val remoteBase = cipher(password = "remote-password")
        val remote = remoteBase.copy(
            login = remoteBase.login?.copy(
                totp = "remote-totp",
            ),
        )

        val resolution = resolveCipherConflict(
            base = null,
            local = local,
            remote = remote,
            at = MERGE_REVISION,
            preserveDisplacedSecretsInPasswordHistory = false,
            gpgCertificateMaterialReconciler = UnexpectedGpgReconciler,
            gpgKeyMetadataResolver = null,
        )

        assertEquals(CipherConflictResolution.Mode.RemoteFallback, resolution.mode)
        assertFalse(resolution.requiresRemoteWrite)
        assertEquals(remote, resolution.cipher)
        assertEquals(emptyList(), resolution.cipher.passwordHistory)
    }

    private fun cipher(
        password: String? = "base-password",
    ) = BitwardenCipher(
        accountId = "account-1",
        cipherId = "cipher-1",
        revisionDate = BASE_REVISION,
        service = BitwardenService(
            remote = BitwardenService.Remote(
                id = "remote-cipher-1",
                revisionDate = BASE_REVISION,
                deletedDate = null,
            ),
            version = BitwardenService.VERSION,
        ),
        name = "Base name",
        notes = "Base notes",
        favorite = false,
        reprompt = BitwardenCipher.RepromptType.None,
        type = BitwardenCipher.Type.Login,
        login = BitwardenCipher.Login(
            password = password,
            uris = emptyList(),
        ),
    )

    private companion object {
        val BASE_REVISION = Instant.parse("2024-01-01T00:00:00Z")
        val LOCAL_REVISION = Instant.parse("2024-01-02T00:00:00Z")
        val REMOTE_REVISION = Instant.parse("2024-01-03T00:00:00Z")
        val MERGE_REVISION = Instant.parse("2024-01-04T00:00:00Z")
    }
}
