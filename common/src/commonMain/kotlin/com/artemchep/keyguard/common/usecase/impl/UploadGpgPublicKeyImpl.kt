package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGpgKeyserverUploadResult
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.model.UploadGpgPublicKeyRequest
import com.artemchep.keyguard.common.model.UploadGpgPublicKeyResult
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconciler
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentFingerprint
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRecorder
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.UploadGpgPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Clock

class UploadGpgPublicKeyImpl(
    private val getCiphers: GetCiphers,
    private val getGpgKeyserverConfig: GetGpgKeyserverConfig,
    private val keyserverClient: GpgKeyserverClient,
    keyserverStateRepository: GpgKeyserverStateRepository,
    metadataResolver: GpgKeyMetadataResolver,
    reconciler: GpgCertificateMaterialReconciler,
) : UploadGpgPublicKey {
    private val stateRecorder = GpgKeyserverStateRecorder(
        repository = keyserverStateRepository,
        reconciler = reconciler,
        resolver = metadataResolver,
    )

    constructor(
        directDI: DirectDI,
    ) : this(
        getCiphers = directDI.instance(),
        getGpgKeyserverConfig = directDI.instance(),
        keyserverClient = directDI.instance(),
        keyserverStateRepository = directDI.instance(),
        metadataResolver = directDI.instance(),
        reconciler = directDI.instance(),
    )

    override fun invoke(
        request: UploadGpgPublicKeyRequest,
    ): IO<UploadGpgPublicKeyResult> = ioEffect(Dispatchers.Default) {
        val (cipher, publicKeyArmored) = getCiphers.requireGpgPublicKeyCipher(
            cipherId = request.cipherId,
            accountId = request.accountId,
        )
        val expectedFingerprint = cipher.getGpgAgentFingerprint()
            ?.normalizeGpgFingerprint()
            ?.takeIf { it.isNotEmpty() }

        val config = getGpgKeyserverConfig().first()
        val uploaded = keyserverClient.upload(
            publicKeyArmored = publicKeyArmored,
            config = config,
        ).bind()
        // Never request verification for a key with a different fingerprint.
        val confirmedFingerprint = uploaded.requireFingerprintMatches(expectedFingerprint)

        // The server and the user may normalize addresses differently.
        val requestedEmails = request.verifyEmails
            .mapTo(HashSet()) { it.trim().lowercase() }
        fun Set<String>.requested() = filterTo(mutableSetOf()) { it.lowercase() in requestedEmails }
        val verifiableEmails = uploaded.verifiableEmails.requested()
        val alreadyPublishedEmails = uploaded.publishedEmails.requested()

        val result = if (verifiableEmails.isNotEmpty()) {
            val token = checkNotNull(uploaded.token) {
                "Keyserver did not return a verification token."
            }
            keyserverClient.requestVerify(
                token = token,
                addresses = verifiableEmails,
                config = config,
            ).bind()
                // The follow-up reply must describe the same key.
                .also { it.requireFingerprintMatches(expectedFingerprint ?: confirmedFingerprint) }
        } else {
            uploaded
        }

        if (confirmedFingerprint != null) {
            val publicationStatus = if (result.publishedEmails.isNotEmpty()) {
                GpgKeyserverVerificationStatus.VERIFIED
            } else {
                GpgKeyserverVerificationStatus.FOUND_UNVERIFIED
            }
            stateRecorder.record(
                fingerprint = confirmedFingerprint,
                cipherIds = setOf(cipher.id),
                publicCertificates = listOf(publicKeyArmored),
                publicationStatus = publicationStatus,
                sourceKeyserver = config.url,
                checkedAt = Clock.System.now(),
                refreshed = false,
            ).bind()
        }

        UploadGpgPublicKeyResult(
            verificationRequestedEmails = verifiableEmails,
            alreadyPublishedEmails = alreadyPublishedEmails,
        )
    }

    /** Fingerprint confirmed by the keyserver, `null` on HKP. Fails if it names another key. */
    private fun DGpgKeyserverUploadResult.requireFingerprintMatches(
        expectedFingerprint: String?,
    ): String? {
        val confirmed = fingerprint ?: return null
        check(expectedFingerprint == null || expectedFingerprint == confirmed) {
            "Keyserver confirmed a public key with an unexpected fingerprint."
        }
        return confirmed
    }
}
