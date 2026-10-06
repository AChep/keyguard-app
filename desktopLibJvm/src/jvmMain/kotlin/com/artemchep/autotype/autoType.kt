package com.artemchep.autotype

import com.artemchep.jna.withDesktopLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public suspend fun autoType(payload: String): Unit = withContext(Dispatchers.IO) {
    withDesktopLib { lib ->
        autoTypeOrThrow(
            lib = lib,
            payload = payload,
        )
    }
}
