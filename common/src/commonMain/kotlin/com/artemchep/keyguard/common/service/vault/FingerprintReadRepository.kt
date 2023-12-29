package com.artemchep.keyguard.common.service.vault

import com.artemchep.keyguard.common.model.Fingerprint
import kotlinx.coroutines.flow.Flow

/**
 * @author Artem Chepurnyi
 */
interface FingerprintReadRepository {
    /**
     * Returns a flow of tokens to help to identify the password and
     * generate a master key.
     */
    fun get(): Flow<Fingerprint?>
}
