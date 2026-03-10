package com.artemchep.autotype

import com.artemchep.jna.withDesktopLib

public suspend fun autoType(payload: String): Unit = withDesktopLib { lib ->
    autoTypeOrThrow(
        lib = lib,
        payload = payload,
    )
}
