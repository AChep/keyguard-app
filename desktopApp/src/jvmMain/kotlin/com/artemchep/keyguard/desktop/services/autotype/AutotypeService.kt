package com.artemchep.keyguard.desktop.services.autotype

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.autotype.AutotypeLogin

interface AutotypeService {
    fun captureTarget(): Long

    /** Prompts for the permission to send input if it has not been granted yet. */
    suspend fun requestPermission(): Boolean

    fun typeLogin(target: Long, login: AutotypeLogin, isActive: () -> Boolean): IO<AutotypeResult>
}

enum class AutotypeResult {
    Success,
    PermissionRequired,
    Unavailable,
    InvalidText,
    Interrupted,
    InputFailed,
    Busy,
    KeysHeld,
}
