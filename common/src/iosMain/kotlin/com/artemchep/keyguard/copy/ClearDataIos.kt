package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.platform.iosKeyguardCacheDirectory
import com.artemchep.keyguard.platform.iosKeyguardDataDirectory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

object ClearDataIos : ClearData {
    @OptIn(ExperimentalForeignApi::class)
    override fun invoke(): IO<Unit> = ioEffect {
        listOf(
            iosKeyguardDataDirectory(),
            iosKeyguardCacheDirectory(),
        ).forEach { directory ->
            NSFileManager.defaultManager.removeItemAtPath(
                path = directory.value,
                error = null,
            )
        }
        Unit
    }
}
