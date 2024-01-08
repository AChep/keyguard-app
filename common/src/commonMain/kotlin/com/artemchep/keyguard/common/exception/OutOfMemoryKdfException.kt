package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.StringResource

open class OutOfMemoryKdfException(
    m: String?,
    e: Throwable?,
) : Exception(m, e), Readable {
    override val title: StringResource
        get() = Res.strings.error_failed_generate_kdf_hash_oom
}
