package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverLocalKey
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.crypto.GPG_TEST_CV25519_PRIMARY_FINGERPRINT
import com.artemchep.keyguard.crypto.GPG_TEST_CV25519_PUBLIC_KEY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Instant

private val instant: Instant = Instant.parse("2024-01-01T00:00:00Z")

/** A secure note holding a GPG key pair, as the GPG agent stores it. */
internal fun createGpgSecret(
    fingerprint: String = GPG_TEST_CV25519_PRIMARY_FINGERPRINT,
    publicKey: String = GPG_TEST_CV25519_PUBLIC_KEY,
    cipherId: String = "cipher-id",
    accountId: String = "account-id",
) = DSecret(
    id = cipherId,
    accountId = accountId,
    folderId = null,
    organizationId = null,
    collectionIds = emptySet(),
    revisionDate = instant,
    createdDate = instant,
    archivedDate = null,
    deletedDate = null,
    service = BitwardenService(),
    name = "GPG key",
    notes = "",
    favorite = false,
    reprompt = false,
    synced = true,
    fields = listOf(
        DSecret.Field(
            name = GpgAgentFields.PUBLIC_KEY_ARMORED,
            value = publicKey,
            type = DSecret.Field.Type.Hidden,
        ),
        DSecret.Field(
            name = GpgAgentFields.FINGERPRINT,
            value = fingerprint,
            type = DSecret.Field.Type.Text,
        ),
    ),
    type = DSecret.Type.SecureNote,
)

internal class FakeGpgKeyserverStateRepository(
    vararg initial: DGpgKeyserverState,
) : GpgKeyserverStateRepository {
    var localKeys: List<GpgKeyserverLocalKey> = emptyList()
    val saved = initial.associateBy { it.fingerprint.normalizeGpgFingerprint() }
        .toMutableMap()

    override fun getAll(): Flow<List<DGpgKeyserverState>> =
        flowOf(saved.values.toList())

    override fun getByFingerprint(
        fingerprint: String,
    ): Flow<DGpgKeyserverState?> =
        flowOf(saved[fingerprint.normalizeGpgFingerprint()])

    override fun getByCipherId(
        cipherId: String,
    ): Flow<List<DGpgKeyserverState>> =
        flowOf(saved.values.filter { it.cipherId == cipherId })

    override fun put(
        model: DGpgKeyserverState,
    ): IO<Unit> = ioEffect {
        saved[model.fingerprint.normalizeGpgFingerprint()] = model.copy(
            fingerprint = model.fingerprint.normalizeGpgFingerprint(),
        )
    }

    override fun update(
        fingerprint: String,
        transform: (DGpgKeyserverState?, List<GpgKeyserverLocalKey>) -> DGpgKeyserverState,
    ): IO<DGpgKeyserverState> = ioEffect {
        val normalized = fingerprint.normalizeGpgFingerprint()
        transform(saved[normalized], localKeys).also { saved[normalized] = it }
    }

    override fun removeByFingerprint(
        fingerprint: String,
    ): IO<Unit> = ioEffect {
        saved.remove(fingerprint.normalizeGpgFingerprint())
    }

    override fun removeAll(): IO<Unit> = ioEffect {
        saved.clear()
    }
}
