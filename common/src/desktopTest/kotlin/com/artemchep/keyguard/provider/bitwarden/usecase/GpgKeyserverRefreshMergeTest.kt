package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysResult
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconcileResult
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconciler
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialWithheldReason
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.crypto.NativeGpgCertificateMaterialReconciler
import com.artemchep.keyguard.crypto.NativeGpgKeyGenerator
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import com.artemchep.keyguard.crypto.NativeGpgPublicKeyParser
import com.artemchep.keyguard.crypto.armored
import com.artemchep.keyguard.crypto.gpgBouncyCastleProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GpgKeyserverRefreshMergeTest {
    @Test
    fun `a partial server certificate cannot discard the local subkey`() {
        val stored = refreshTestCipher().gpgKey!!
        val refreshed = assertNotNull(refresh(stored, partialCertificate))

        assertEquals(REFRESH_PUBLIC_KEY, refreshed.publicKeyArmored)
        assertEquals(stored.metadata, refreshed.metadata)
        assertEquals(refreshed, refresh(refreshed, partialCertificate))
    }

    @Test
    fun `a fuller server certificate adds its subkey and rebuilds metadata`() {
        val stored = refreshTestCipher(publicKey = partialCertificate).gpgKey!!
        val refreshed = assertNotNull(refresh(stored, REFRESH_PUBLIC_KEY))

        assertEquals(REFRESH_PUBLIC_KEY, refreshed.publicKeyArmored)
        assertEquals(2, refreshed.metadata?.certificates?.single()?.components?.size)
        assertEquals(1, stored.metadata?.certificates?.single()?.components?.size)
    }

    @Test
    fun `refresh uses evidence added while the lookup was in flight`() = runTest {
        GpgKeyserverRefreshTestFixture(
            initial = listOf(refreshTestCipher(publicKey = partialCertificate)),
        ).use { fixture ->
            fixture.beforeLookup = { fixture.update { refreshTestCipher().copy(notes = it.notes) } }
            fixture.lookup = { DGpgKeyserverResult(REFRESH_FINGERPRINT, publicKeyArmored = partialCertificate) }

            assertEquals(RefreshGpgPublicKeysResult(1, 0, 0), fixture.useCase(fixture.request).bind())
            assertEquals(REFRESH_PUBLIC_KEY, fixture.row().data_.gpgKey?.publicKeyArmored)
            assertEquals(0, fixture.backupDirtyCount)
        }
    }

    @Test
    fun `a server response without the locally held revocation cannot undo it`() {
        val generated = NativeGpgKeyGenerator.generate(
            GpgKeyConfig.Modern(userId = "Refresh Test <refresh@example.test>"),
        )
        val publicRing = ring(generated.publicKeyArmored)
        val primary = publicRing.publicKey
        val secretRing = PGPSecretKeyRing(
            PGPUtil.getDecoderStream(generated.privateKeyArmored.byteInputStream()),
            JcaKeyFingerprintCalculator(),
        )
        val signer = PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(primary.algorithm, HashAlgorithmTags.SHA512)
                .setProvider(gpgBouncyCastleProvider),
            primary,
        )
        signer.init(PGPSignature.KEY_REVOCATION, secretRing.secretKey.extractPrivateKey(null))
        signer.setHashedSubpackets(
            PGPSignatureSubpacketGenerator().apply {
                setIssuerFingerprint(false, primary)
                setSignatureCreationTime(false, primary.creationTime)
            }.generate(),
        )
        val revoked = PGPPublicKeyRing.insertPublicKey(
            publicRing,
            PGPPublicKey.addCertification(primary, signer.generateCertification(primary)),
        ).armored()
        val key = BitwardenCipher.GpgKey(
            publicKeyArmored = revoked,
            fingerprint = generated.fingerprint,
        )
        val refreshed = assertNotNull(
            key.withGpgKeyserverRefresh(
                expectedPrimaryFingerprint = generated.fingerprint,
                result = DGpgKeyserverResult(generated.fingerprint, publicKeyArmored = generated.publicKeyArmored),
                reconciler = NativeGpgCertificateMaterialReconciler,
                resolver = NativeGpgKeyMetadataResolver,
            ),
        )
        val parsed = assertIs<GpgPublicKeyParseResult.Success>(
            NativeGpgPublicKeyParser.parse(refreshed.publicKeyArmored!!),
        )
        assertTrue(parsed.keys.single().revoked)
    }

    @Test
    fun `local evidence is stored even when the transferable projection is withheld`() {
        val key = refreshTestCipher().gpgKey!!
        val merged = assertIs<GpgCertificateMaterialReconcileResult.Success>(
            NativeGpgCertificateMaterialReconciler.reconcile(
                REFRESH_FINGERPRINT,
                REFRESH_PUBLIC_KEY,
                null,
                partialCertificate,
                null,
            ),
        ).copy(
            transferablePublicCertificate = null,
            withheldReasons = setOf(GpgCertificateMaterialWithheldReason.LocalPublicEvidence),
        )

        val refreshed = assertNotNull(refresh(key, partialCertificate, fixedReconciler { merged }))

        assertEquals(merged.localPublicMaterial, refreshed.publicKeyArmored)
        assertEquals(key.metadata, refreshed.metadata)
    }

    @Test
    fun `reconciliation cancellation and fatal errors are not swallowed`() {
        val key = refreshTestCipher().gpgKey!!
        assertFailsWith<CancellationException> {
            refresh(key, partialCertificate, fixedReconciler { throw CancellationException("cancelled") })
        }
        assertFailsWith<AssertionError> {
            refresh(key, partialCertificate, fixedReconciler { throw AssertionError("fatal") })
        }
    }

    private fun refresh(
        key: BitwardenCipher.GpgKey,
        incoming: String,
        reconciler: GpgCertificateMaterialReconciler = NativeGpgCertificateMaterialReconciler,
    ) = key.withGpgKeyserverRefresh(
        expectedPrimaryFingerprint = REFRESH_FINGERPRINT,
        result = DGpgKeyserverResult(REFRESH_FINGERPRINT, publicKeyArmored = incoming),
        reconciler = reconciler,
        resolver = NativeGpgKeyMetadataResolver,
    )

    private fun fixedReconciler(
        result: () -> GpgCertificateMaterialReconcileResult,
    ) = object : GpgCertificateMaterialReconciler {
        override fun reconcile(
            expectedPrimaryFingerprint: String,
            existingPublicCertificate: String?,
            existingSecretCertificate: String?,
            incomingPublicCertificate: String?,
            incomingSecretCertificate: String?,
        ) = result()
    }

    private companion object {
        val partialCertificate by lazy {
            PGPPublicKeyRing(listOf(ring(REFRESH_PUBLIC_KEY).publicKey)).armored()
        }

        fun ring(armored: String) = PGPPublicKeyRing(
            PGPUtil.getDecoderStream(armored.byteInputStream()),
            JcaKeyFingerprintCalculator(),
        )
    }
}
