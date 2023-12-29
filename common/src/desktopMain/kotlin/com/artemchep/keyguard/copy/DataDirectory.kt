package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import kotlinx.coroutines.Dispatchers
import net.harawata.appdirs.AppDirsFactory
import org.kodein.di.DirectDI

class DataDirectory(
) {
    companion object {
        private val APP_NAME = "keyguard"
        private val APP_AUTHOR = "ArtemChepurnyi"
    }

    constructor(directDI: DirectDI) : this()

    fun data(): IO<String> = ioEffect(Dispatchers.IO) {
        val appDirs = AppDirsFactory.getInstance()
        appDirs.getUserDataDir(APP_NAME, null, APP_AUTHOR)
    }

    fun config(): IO<String> = ioEffect(Dispatchers.IO) {
        val appDirs = AppDirsFactory.getInstance()
        appDirs.getUserConfigDir(APP_NAME, null, APP_AUTHOR, true)
    }

    fun cache(): IO<String> = ioEffect(Dispatchers.IO) {
        val appDirs = AppDirsFactory.getInstance()
        appDirs.getUserCacheDir(APP_NAME, null, APP_AUTHOR)
    }

    fun downloads(): IO<String> = ioEffect(Dispatchers.IO) {
        val appDirs = AppDirsFactory.getInstance()
        appDirs.getUserDownloadsDir(APP_NAME, null, APP_AUTHOR)
    }
}
