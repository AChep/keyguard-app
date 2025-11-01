package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

class KeePassFileAlreadyExistsException(
    e: Throwable? = null,
) : IllegalStateException("Failed to create a file. Replacing existing file is not allowed", e), Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_failed_file_already_exists)
}
