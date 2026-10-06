package com.artemchep.keyguard.desktop.services.autotype

import com.artemchep.autotype.AutoTypeLoginStatus
import com.artemchep.autotype.autoTypeCaptureTarget
import com.artemchep.autotype.autoTypeLogin
import com.artemchep.autotype.autoTypePermission
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.autotype.AutotypeLogin
import com.artemchep.keyguard.common.usecase.GetAutotypeSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class AutotypeServiceNative(
    private val getAutotypeSpeed: GetAutotypeSpeed,
) : AutotypeService {

    override fun captureTarget(): Long = autoTypeCaptureTarget()

    // The permission query is an out-of-process call; keep it off the UI dispatcher.
    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.IO) {
        autoTypePermission()
    }

    override fun typeLogin(target: Long, login: AutotypeLogin, isActive: () -> Boolean) = ioEffect {
        val speed = getAutotypeSpeed().first()
        val status = autoTypeLogin(
            target = target,
            username = login.username,
            password = login.password,
            delayMultiplier = speed.delayMultiplier,
            isActive = isActive,
        )
        when (status) {
            AutoTypeLoginStatus.SUCCESS -> AutotypeResult.Success
            AutoTypeLoginStatus.UNAVAILABLE -> AutotypeResult.Unavailable
            AutoTypeLoginStatus.INVALID_TEXT -> AutotypeResult.InvalidText
            AutoTypeLoginStatus.INTERRUPTED -> AutotypeResult.Interrupted
            AutoTypeLoginStatus.INPUT_FAILED -> AutotypeResult.InputFailed
            AutoTypeLoginStatus.BUSY -> AutotypeResult.Busy
            AutoTypeLoginStatus.KEYS_HELD -> AutotypeResult.KeysHeld
        }
    }
}
