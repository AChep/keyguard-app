package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.model.canEdit
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationError
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationResult
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationService
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationServiceUnsupported
import com.artemchep.keyguard.common.service.crypto.toGpgRevocationKeyCandidates
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentFingerprint
import com.artemchep.keyguard.common.usecase.ChangeGpgKeyExpirationById
import com.artemchep.keyguard.common.usecase.ChangeGpgKeyExpirationByIdRequest
import com.artemchep.keyguard.common.usecase.ChangeGpgKeyExpirationByIdResult
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.instance
import org.kodein.di.instanceOrNull

class ChangeGpgKeyExpirationByIdImpl internal constructor(
    private val getCiphers: GetCiphers,
    private val gpgKeyExpirationService: GpgKeyExpirationService,
    private val commitUpdatedKey: GpgKeyExpirationCommitter,
) : ChangeGpgKeyExpirationById {
    constructor(
        getCiphers: GetCiphers,
        modifyCipherById: ModifyCipherById,
        gpgKeyExpirationService: GpgKeyExpirationService,
    ) : this(
        getCiphers = getCiphers,
        gpgKeyExpirationService = gpgKeyExpirationService,
        commitUpdatedKey = modifyCipherById.asGpgKeyExpirationCommitter(),
    )

    constructor(directDI: DirectDI) : this(
        getCiphers = directDI.instance(),
        modifyCipherById = directDI.instance(),
        gpgKeyExpirationService = directDI.instanceOrNull()
            ?: GpgKeyExpirationServiceUnsupported,
    )

    override val isSupported: Boolean
        get() = gpgKeyExpirationService.isSupported

    override fun invoke(
        request: ChangeGpgKeyExpirationByIdRequest,
    ): IO<ChangeGpgKeyExpirationByIdResult> = ioEffect(Dispatchers.IO) {
        if (!isSupported) {
            return@ioEffect ChangeGpgKeyExpirationByIdResult.CryptoFailure(
                GpgKeyExpirationError.UnsupportedPlatform,
            )
        }
        val ciphers = getCiphers().first()
        val cipher = ciphers
            .firstOrNull { it.id == request.cipherId }
            ?: return@ioEffect notChanged(
                ChangeGpgKeyExpirationByIdResult.NotChanged.Reason.NotFound,
            )
        if (!cipher.canEdit()) {
            return@ioEffect notChanged(
                ChangeGpgKeyExpirationByIdResult.NotChanged.Reason.NotEditable,
            )
        }
        val sourceKey = cipher.gpgKey
            ?.toGpgKeyMaterial()
            ?: return@ioEffect notChanged(
                ChangeGpgKeyExpirationByIdResult.NotChanged.Reason.MissingGpgKey,
            )
        if (
            sourceKey.publicKeyArmored != request.expectedPublicKeyArmored ||
            cipher.getGpgAgentFingerprint() != request.expectedKeyFingerprint
        ) {
            return@ioEffect notChanged(
                ChangeGpgKeyExpirationByIdResult.NotChanged.Reason.Conflict,
            )
        }
        val candidateRevocationKeys = ciphers.toGpgRevocationKeyCandidates()
        val updatedKey = when (
            val updateResult = gpgKeyExpirationService.update(
                GpgKeyExpirationRequest(
                    key = sourceKey,
                    change = request.change,
                    candidateRevocationKeys = candidateRevocationKeys,
                ),
            )
        ) {
            is GpgKeyExpirationResult.Success -> updateResult.key
            is GpgKeyExpirationResult.Error -> return@ioEffect ChangeGpgKeyExpirationByIdResult.CryptoFailure(
                updateResult.reason,
            )
        }

        if (
            commitUpdatedKey(
                cipherId = request.cipherId,
                sourceKey = sourceKey,
                updatedKey = updatedKey,
            ).bind()
        ) {
            ChangeGpgKeyExpirationByIdResult.Success
        } else {
            notChanged(ChangeGpgKeyExpirationByIdResult.NotChanged.Reason.Conflict)
        }
    }

    private fun notChanged(
        reason: ChangeGpgKeyExpirationByIdResult.NotChanged.Reason,
    ) = ChangeGpgKeyExpirationByIdResult.NotChanged(reason)
}

internal fun interface GpgKeyExpirationCommitter {
    operator fun invoke(
        cipherId: String,
        sourceKey: GpgKeyMaterial,
        updatedKey: GpgKeyMaterial,
    ): IO<Boolean>
}

private fun ModifyCipherById.asGpgKeyExpirationCommitter(): GpgKeyExpirationCommitter =
    GpgKeyExpirationCommitter { cipherId, sourceKey, updatedKey ->
        invoke(
            cipherIds = setOf(cipherId),
        ) { model ->
            val storedKey = model.data_.gpgKey
            if (storedKey?.toGpgKeyMaterial() != sourceKey) {
                model
            } else {
                model.copy(
                    data_ = model.data_.copy(
                        gpgKey = storedKey.withGpgKeyMaterial(updatedKey),
                    ),
                )
            }
        }.map { cipherId in it }
    }

private fun DSecret.GpgKey.toGpgKeyMaterial(): GpgKeyMaterial = GpgKeyMaterial(
    privateKeyArmored = privateKeyArmored.orEmpty(),
    publicKeyArmored = publicKeyArmored.orEmpty(),
    fingerprint = fingerprint.orEmpty(),
    metadata = metadata,
)

private fun BitwardenCipher.GpgKey.toGpgKeyMaterial(): GpgKeyMaterial = GpgKeyMaterial(
    privateKeyArmored = privateKeyArmored.orEmpty(),
    publicKeyArmored = publicKeyArmored.orEmpty(),
    fingerprint = fingerprint.orEmpty(),
    metadata = metadata,
)

private fun BitwardenCipher.GpgKey.withGpgKeyMaterial(
    material: GpgKeyMaterial,
): BitwardenCipher.GpgKey = copy(
    privateKeyArmored = material.privateKeyArmored,
    publicKeyArmored = material.publicKeyArmored,
    fingerprint = material.fingerprint,
    metadata = material.metadata,
)
