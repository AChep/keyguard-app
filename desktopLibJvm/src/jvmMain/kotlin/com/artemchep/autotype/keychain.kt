package com.artemchep.autotype

import com.artemchep.jna.util.asMemory
import com.artemchep.jna.withDesktopLib

public suspend fun keychainAddPassword(
    id: String,
    password: String,
): Unit = withDesktopLib { lib ->
    lib.keychainAddPassword(
        id = id
            .asMemory()
            .let(::register),
        password = password
            .asMemory()
            .let(::register),
    )
}

public suspend fun keychainGetPassword(id: String): String = withDesktopLib { lib ->
    val result = lib.keychainGetPassword(
        id = id
            .asMemory()
            .let(::register),
    )
    try {
        result.getString(0)
    } finally {
        lib.free(result)
    }
}

public suspend fun keychainDeletePassword(id: String): Boolean = withDesktopLib { lib ->
    lib.keychainDeletePassword(
        id = id
            .asMemory()
            .let(::register),
    )
}

public suspend fun keychainContainsPassword(id: String): Boolean = withDesktopLib { lib ->
    lib.keychainContainsPassword(
        id = id
            .asMemory()
            .let(::register),
    )
}
