package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

open class OutOfMemoryKdfException(
    m: String?,
    e: Throwable?,
) : Exception(m, e), Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_failed_generate_kdf_hash_oom)
}
