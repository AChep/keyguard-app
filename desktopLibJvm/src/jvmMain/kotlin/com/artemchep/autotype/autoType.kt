package com.artemchep.autotype

import com.artemchep.jna.util.asMemory
import com.artemchep.jna.withDesktopLib

public suspend fun autoType(payload: String): Unit = withDesktopLib { lib ->
    lib.autoType(
        payload = payload
            .asMemory()
            .let(::register),
    )
}
