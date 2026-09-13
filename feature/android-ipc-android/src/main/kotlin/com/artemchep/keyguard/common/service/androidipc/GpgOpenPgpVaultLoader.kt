package com.artemchep.keyguard.common.service.androidipc

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.filterCiphers
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpCertificationAuthority
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpRing
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVault
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.parsePrimaryKeyInfo
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeySnapshot
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
        val snapshot = publicKeyRepository.getSnapshot().bind()
        val now = Clock.System.now()
        // Parse each distinct document once, so the catalog and the trust
        // authorities share one notion of a valid document.
        val parsedInfoByDocument = buildList {
            snapshot.publicKeys.mapTo(this) { row ->
                row.publicKeyArmored to row.primaryFingerprint
            }
            snapshot.certificationAuthorities.mapTo(this) { row ->
                row.publicKeyArmored to row.primaryFingerprint
            }
        }
            .distinct()
            .associateWith { (armored, fingerprint) ->
                runCatching {
                    publicKeyParser.parsePrimaryKeyInfo(
                        armored = armored,
                        fingerprint = fingerprint,
                    )
                }.getOrNull()
            }
        val rings = snapshot.publicKeys.mapNotNull { row ->
            val info = parsedInfoByDocument[row.publicKeyArmored to row.primaryFingerprint]
                ?: return@mapNotNull null
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
        val authorityRows = snapshot.certificationAuthorities
            .distinctBy { row -> row.publicKeyArmored to row.primaryFingerprint }
        val authorities = authorityRows.mapNotNull { row ->
            parsedInfoByDocument[row.publicKeyArmored to row.primaryFingerprint]
                ?: return@mapNotNull null
            GpgOpenPgpCertificationAuthority(
                publicKey = GpgOpenPgpPublicKey(row.publicKeyArmored),
                primaryFingerprint = row.primaryFingerprint,
            )
        }
        logLoadedVault(requestReference, snapshot, rings, authorityRows.size, authorities.size)
        GpgOpenPgpVault(
            session = session,
            rings = rings,
            certificationAuthorities = authorities,
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
            certificationAuthorities = vault.certificationAuthorities,
        )
    }

    private fun logLoadedVault(
        requestReference: String,
        snapshot: GpgPublicKeySnapshot,
        rings: List<GpgOpenPgpRing>,
        authorityRowCount: Int,
        authorityCount: Int,
    ) {
        logRepository.postDebug(TAG) {
            "request=$requestReference catalog_rows=${snapshot.publicKeys.size} " +
                "parsed_rings=${rings.size} " +
                "parse_failures=${snapshot.publicKeys.size - rings.size} " +
                "certified_emails=${rings.sumOf { it.info.emails.size }} " +
                "encryption_capable=${rings.count(GpgOpenPgpRing::canEncrypt)} " +
                "encryption_incapable=${rings.count { !it.canEncrypt }} " +
                "revoked=${rings.count { it.info.revoked }} " +
                "expired=${rings.count { it.isExpired }}" +
                " certification_authorities=$authorityCount" +
                " authority_parse_failures=${authorityRowCount - authorityCount}"
        }
    }

    private companion object {
        private const val TAG = "OpenPgpRecipientLookup"
    }
}
