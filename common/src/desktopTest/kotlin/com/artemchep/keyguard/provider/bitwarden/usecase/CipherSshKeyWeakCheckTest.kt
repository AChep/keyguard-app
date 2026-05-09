package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.usecase.CipherSshKeyWeakCheck
import com.artemchep.keyguard.common.usecase.impl.WatchtowerSshKeyStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.crypto.KeyPairGeneratorJvm
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct

class CipherSshKeyWeakCheckTest {
    private val keyPairGenerator = KeyPairGeneratorJvm(
        cryptoGenerator = CryptoGeneratorJvm(),
    )
    private val check = CipherSshKeyWeakCheckImpl(
        keyPairGenerator = keyPairGenerator,
    )

    private val rsa1024 by lazy {
        generateKeyPair(KeyPairGenerator.RsaLength.B1024)
    }
    private val rsa2048 by lazy {
        generateKeyPair(KeyPairGenerator.RsaLength.B2048)
    }
    private val rsa3072 by lazy {
        generateKeyPair(KeyPairGenerator.RsaLength.B3072)
    }
    private val rsa4096 by lazy {
        generateKeyPair(KeyPairGenerator.RsaLength.B4096)
    }
    private val ed25519 by lazy {
        keyPairGenerator.populate(keyPairGenerator.ed25519())
    }

    @Test
    fun `rsa key below 2048 bits is weak`() {
        val secret = createSshSecret("rsa-1024", rsa1024)

        assertTrue(check(secret))
    }

    @Test
    fun `rsa key at 2048 bits is not weak`() {
        val secret = createSshSecret("rsa-2048", rsa2048)

        assertFalse(check(secret))
    }

    @Test
    fun `rsa keys above 2048 bits are not weak`() {
        val rsa3072Secret = createSshSecret("rsa-3072", rsa3072)
        val rsa4096Secret = createSshSecret("rsa-4096", rsa4096)

        assertFalse(check(rsa3072Secret))
        assertFalse(check(rsa4096Secret))
    }

    @Test
    fun `ed25519 key is not weak`() {
        val secret = createSshSecret("ed25519", ed25519)

        assertFalse(check(secret))
    }

    @Test
    fun `non ssh item is not weak`() {
        val secret = createSecret(
            id = "login",
            type = DSecret.Type.Login,
        )

        assertFalse(check(secret))
    }

    @Test
    fun `ssh item with missing private key is not weak`() {
        val secret = createSshSecret(
            id = "missing-private-key",
            privateKey = null,
            publicKey = rsa1024.publicKey.ssh,
            fingerprint = rsa1024.publicKey.fingerprint,
        )

        assertFalse(check(secret))
    }

    @Test
    fun `ssh item with missing public key still reports weak key material`() {
        val secret = createSshSecret(
            id = "missing-public-key",
            privateKey = rsa1024.privateKey.ssh,
            publicKey = null,
            fingerprint = rsa1024.publicKey.fingerprint,
        )

        assertTrue(check(secret))
    }

    @Test
    fun `ssh item with missing fingerprint still reports weak key material`() {
        val secret = createSshSecret(
            id = "missing-fingerprint",
            privateKey = rsa1024.privateKey.ssh,
            publicKey = rsa1024.publicKey.ssh,
            fingerprint = null,
        )

        assertTrue(check(secret))
    }

    @Test
    fun `mismatched public key does not hide weak rsa private key`() {
        val secret = createSshSecret(
            id = "mismatched-public-key",
            privateKey = rsa1024.privateKey.ssh,
            publicKey = ed25519.publicKey.ssh,
            fingerprint = ed25519.publicKey.fingerprint,
        )

        assertTrue(check(secret))
    }

    @Test
    fun `ssh item with unparsable private key is not weak`() {
        val secret = createSshSecret(
            id = "unparsable",
            privateKey = "not a private key",
            publicKey = "not a public key",
            fingerprint = "SHA256:invalid",
        )

        assertFalse(check(secret))
    }

    @Test
    fun `ignored alert metadata does not suppress raw weak key check`() {
        val secret = createSshSecret(
            id = "ignored",
            keyPair = rsa1024,
            ignoredAlerts = mapOf(DWatchtowerAlertType.WEAK_SSH_KEY to TEST_INSTANT),
        )

        assertTrue(check(secret))
    }

    @Test
    fun `watchtower processor reports weak ssh key threat with private key value`() = runTest {
        val weak = createSshSecret("weak", rsa1024)
        val strong = createSshSecret("strong", rsa2048)
        val processor = WatchtowerSshKeyStrength(check)

        val results = processor.process(listOf(weak, strong))

        val weakResult = results.single { it.cipher.id == weak.id }
        val strongResult = results.single { it.cipher.id == strong.id }
        assertTrue(weakResult.threat)
        assertEquals(rsa1024.privateKey.ssh, weakResult.value)
        assertFalse(strongResult.threat)
        assertEquals(rsa2048.privateKey.ssh, strongResult.value)
    }

    @Test
    fun `watchtower processor suppresses threat for ignored weak ssh key`() = runTest {
        val ignored = createSshSecret(
            id = "ignored",
            keyPair = rsa1024,
            ignoredAlerts = mapOf(DWatchtowerAlertType.WEAK_SSH_KEY to TEST_INSTANT),
        )
        val processor = WatchtowerSshKeyStrength(check)

        val result = processor.process(listOf(ignored)).single()

        assertFalse(result.threat)
        assertEquals(rsa1024.privateKey.ssh, result.value)
    }

    @Test
    fun `weak ssh key filter matches only weak ssh keys`() = runTest {
        val weak = createSshSecret("weak", rsa1024)
        val strong = createSshSecret("strong", rsa2048)
        val ignored = createSshSecret(
            id = "ignored",
            keyPair = rsa1024,
            ignoredAlerts = mapOf(DWatchtowerAlertType.WEAK_SSH_KEY to TEST_INSTANT),
        )
        val ciphers = listOf(weak, strong, ignored)
        val directDI = DI {
            bindSingleton<CipherSshKeyWeakCheck> { check }
        }.direct

        val predicate = DFilter.ByWeakSshKeys.prepare(
            directDI = directDI,
            ciphers = ciphers,
        )

        assertTrue(predicate(weak))
        assertFalse(predicate(strong))
        assertFalse(predicate(ignored))
        assertEquals(1, DFilter.ByWeakSshKeys.count(directDI, ciphers))
    }

    private fun generateKeyPair(
        length: KeyPairGenerator.RsaLength,
    ): KeyPair = keyPairGenerator.populate(
        keyPairGenerator.rsa(length = length),
    )

    private fun createSshSecret(
        id: String,
        keyPair: KeyPair,
        ignoredAlerts: Map<DWatchtowerAlertType, Instant> = emptyMap(),
    ): DSecret = createSshSecret(
        id = id,
        privateKey = keyPair.privateKey.ssh,
        publicKey = keyPair.publicKey.ssh,
        fingerprint = keyPair.publicKey.fingerprint,
        ignoredAlerts = ignoredAlerts,
    )

    private fun createSshSecret(
        id: String,
        privateKey: String?,
        publicKey: String?,
        fingerprint: String?,
        ignoredAlerts: Map<DWatchtowerAlertType, Instant> = emptyMap(),
    ): DSecret = createSecret(
        id = id,
        type = DSecret.Type.SshKey,
        sshKey = DSecret.SshKey(
            privateKey = privateKey,
            publicKey = publicKey,
            fingerprint = fingerprint,
        ),
        ignoredAlerts = ignoredAlerts,
    )

    private fun createSecret(
        id: String,
        type: DSecret.Type,
        sshKey: DSecret.SshKey? = null,
        ignoredAlerts: Map<DWatchtowerAlertType, Instant> = emptyMap(),
    ): DSecret = DSecret(
        id = id,
        accountId = "account-id",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = TEST_INSTANT,
        createdDate = TEST_INSTANT,
        archivedDate = null,
        deletedDate = null,
        service = BitwardenService(),
        name = id,
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        ignoredAlerts = ignoredAlerts,
        type = type,
        sshKey = sshKey,
    )

    private companion object {
        val TEST_INSTANT: Instant = Instant.parse("2024-01-01T00:00:00Z")
    }
}
