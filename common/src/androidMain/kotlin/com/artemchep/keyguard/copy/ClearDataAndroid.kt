package com.artemchep.keyguard.copy

import android.app.ActivityManager
import android.app.Application
import androidx.core.content.getSystemService
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.usecase.ClearData
import kotlinx.coroutines.Dispatchers

class ClearDataAndroid(
    private val application: Application,
) : ClearData {

    override fun invoke(): IO<Unit> = ioEffect(Dispatchers.Main) {
        application.getSystemService<ActivityManager>()
            ?.clearApplicationUserData()
    }
}
