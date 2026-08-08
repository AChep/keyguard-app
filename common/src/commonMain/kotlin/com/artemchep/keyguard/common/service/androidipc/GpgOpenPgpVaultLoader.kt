package com.artemchep.keyguard.common.service.androidipc

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.filterCiphers
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpRing
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVault
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.parsePrimaryKeyInfo
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.toGpgAgentSecretOrNull
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.time.Clock

/**
 * Loads the OpenPGP key rings available over Android IPC: the public
 * catalog first, and, once the caller is authorized, the matching
 * private key material from the unlocked vault.
 */
internal class GpgOpenPgpVaultLoader(
    private val getVaultSession: GetVaultSession,
    private val getGpgAgentFilter: GetGpgAgentFilter,
    private val publicKeyParser: GpgPublicKeyParser,
    private val publicKeyRepository: GpgPublicKeyRepository,
    private val logRepository: LogRepository,
) {
    suspend fun load(
        requestReference: String,
    ): GpgOpenPgpVault = withContext(Dispatchers.IO) {
        val session = getVaultSession.valueOrNull as? MasterSession.Key
        val publicRows = publicKeyRepository.getPublicKeys().bind()
        val now = Clock.System.now()
        var parseFailures = 0
        val publicRings = publicRows
            .mapNotNull { row ->
                val info = runCatching {
                    publicKeyParser.parsePrimaryKeyInfo(
                        armored = row.publicKeyArmored,
                        fingerprint = row.primaryFingerprint,
                    )
                }.getOrNull()
                if (info == null) {
                    parseFailures += 1
                    return@mapNotNull null
                }
                GpgOpenPgpRing(
                    accountId = row.accountId,
                    cipherId = row.cipherId,
                    name = row.name ?: info.userIds.firstOrNull() ?: info.fingerprint,
                    info = info,
                    hasSigningPrivateMaterial = row.canSign,
                    hasDecryptionPrivateMaterial = row.canDecrypt,
                    privateKeyArmored = null,
                    now = now,
                )
            }
        logRepository.postDebug(TAG) {
            "request=$requestReference catalog_rows=${publicRows.size} " +
                    "parsed_rings=${publicRings.size} parse_failures=$parseFailures " +
                    "certified_emails=${publicRings.sumOf { it.info.emails.size }} " +
                    "encryption_capable=${publicRings.count(GpgOpenPgpRing::canEncrypt)} " +
                    "encryption_incapable=${publicRings.count { !it.canEncrypt }} " +
                    "revoked=${publicRings.count { it.info.revoked }} " +
                    "expired=${publicRings.count { it.isExpired }}"
        }
        GpgOpenPgpVault(
            session = session,
            rings = publicRings,
        )
    }

    suspend fun withPrivateKeys(
        vault: GpgOpenPgpVault,
    ): GpgOpenPgpVault = withContext(Dispatchers.IO) {
        // Re-read the session: it may have been unlocked while this
        // request was waiting for approval.
        val session = getVaultSession.valueOrNull as? MasterSession.Key
            ?: return@withContext vault
        val getCiphers = session.di.direct.instance<GetCiphers>()
        val eligible = getCiphers()
            .first()
            .mapNotNull { cipher ->
                val material = cipher.toGpgAgentSecretOrNull()
                    ?: return@mapNotNull null
                cipher to material
            }
        val filtered = getGpgAgentFilter()
            .first()
            .filterCiphers(
                directDI = session.di.direct,
                items = eligible,
                cipherOf = { it.first },
            )
        val privateMaterials = filtered.associateBy {
            it.first.accountId to it.first.id
        }
        GpgOpenPgpVault(
            session = session,
            rings = vault.rings.map { ring ->
                val material = privateMaterials[ring.accountId to ring.cipherId]
                    ?.second
                ring.copy(
                    privateKeyArmored = material?.privateKeyArmored,
                )
            },
        )
    }

    private companion object {
        private const val TAG = "OpenPgpRecipientLookup"
    }
}
