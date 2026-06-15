package com.artemchep.autotype

import com.artemchep.jna.util.asMemory
import com.artemchep.jna.withDesktopLib

public suspend fun keychainAddPassword(
    id: String,
    password: String,
): Unit = withDesktopLib { lib ->
    keychainAddPasswordOrThrow(
        lib = lib,
        id = id,
        password = password,
    )
}

public suspend fun keychainGetPassword(id: String): String = withDesktopLib { lib ->
    keychainGetPasswordOrThrow(
        lib = lib,
        id = id,
    )
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
