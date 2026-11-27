package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

/**
 * The device does not have enough memory to derive the master
 * key with the chosen key derivation parameters.
 */
class MasterKdfOutOfMemoryException(
    e: Throwable?,
) : RuntimeException("Not enough memory to derive the master key", e),
    Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_master_kdf_out_of_memory)
}
