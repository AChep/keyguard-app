package com.artemchep.keyguard.common.service.vault

import com.artemchep.keyguard.common.exception.MasterPasswordChangedException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintPassword

/**
 * @author Artem Chepurnyi
 */
interface FingerprintReadWriteRepository : FingerprintReadRepository {
    fun put(key: Fingerprint?): IO<Unit>

    /** Atomically transform the latest fingerprint relative to other repository writes. */
    fun update(transform: (Fingerprint?) -> Fingerprint?): IO<Unit>
}

/**
 * Applies the [transform] to the latest fingerprint, if it still has the
 * [master] the caller started from. Otherwise fails with
 * [MasterPasswordChangedException]: an outdated copy, for example one read
 * by another process before a password change, must not overwrite the
 * newer password.
 */
fun FingerprintReadWriteRepository.updateIfMaster(
    master: FingerprintPassword,
    transform: (Fingerprint) -> Fingerprint,
): IO<Unit> = update { current ->
    if (current == null || current.master != master) {
        throw MasterPasswordChangedException()
    }
    transform(current)
}
