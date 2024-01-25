package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res

open class OutOfMemoryKdfException(
    m: String?,
    e: Throwable?,
) : Exception(m, e), Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.strings.error_failed_generate_kdf_hash_oom)
}
