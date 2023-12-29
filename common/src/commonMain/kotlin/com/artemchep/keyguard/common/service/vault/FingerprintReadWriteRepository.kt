package com.artemchep.keyguard.common.service.vault

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.Fingerprint

/**
 * @author Artem Chepurnyi
 */
interface FingerprintReadWriteRepository : FingerprintReadRepository {
    fun put(key: Fingerprint?): IO<Unit>
}
