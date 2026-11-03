package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface ClearData : () -> IO<Unit>

/**
 * What the system keeps for the app outside its data, such as an index of passkeys,
 * cleared before [ClearData] erases the data.
 */
fun interface ClearDataHook {
    /** Throwing stops the erase, so the user can retry it. */
    suspend fun beforeClearData()
}
