package com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.ops

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCr
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.sync.v2.throwIfCancellation
import kotlin.time.Instant

internal class DecodeVaultException(message: String, e: Throwable) : RuntimeException(message, e)

/**
 * Creates a [BitwardenService] that marks an entity as having
 * failed decryption. The entity is persisted locally with an error
 * so the user can see it, but it cannot be edited until re-synced.
 *
 * The [revisionDate] is clamped away from [Instant.DISTANT_FUTURE]
 * to avoid sentinel values leaking into error metadata.
 */
internal fun createDecodingFailedService(
    now: Instant,
    remoteId: String,
    revisionDate: Instant,
    deletedDate: Instant?,
): BitwardenService {
    val errorRevisionDate =
        revisionDate
            .takeUnless { it == Instant.DISTANT_FUTURE }
            ?: now
    return BitwardenService(
        remote =
            BitwardenService.Remote(
                id = remoteId,
                revisionDate = errorRevisionDate,
                deletedDate = deletedDate,
            ),
        error =
            BitwardenService.Error(
                code = BitwardenService.Error.CODE_DECODING_FAILED,
                revisionDate = now,
            ),
        deleted = false,
        version = BitwardenService.VERSION,
    )
}

internal suspend inline fun <T> decodeRemoteOrFallback(
    crossinline decode: suspend () -> T,
    crossinline fallback: suspend (Throwable) -> T,
): T =
    try {
        decode()
    } catch (e: Throwable) {
        e.throwIfCancellation()
        fallback(e)
    }

internal fun syncKeyForOrganization(organizationId: String?): BitwardenCrKey =
    organizationId
        ?.let(BitwardenCrKey::OrganizationToken)
        ?: BitwardenCrKey.UserToken

internal fun buildSyncCodec(
    crypto: BitwardenCr,
    mode: BitwardenCrCta.Mode,
    key: BitwardenCrKey,
): BitwardenCrCta =
    crypto.cta(
        env =
            BitwardenCrCta.BitwardenCrCtaEnv(
                key = key,
            ),
        mode = mode,
    )
