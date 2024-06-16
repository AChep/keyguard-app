package com.artemchep.keyguard.copy

import android.util.Log
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepositoryChild

class LogRepositoryAndroid(
) : LogRepositoryChild {
    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARNING -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
    }
}
