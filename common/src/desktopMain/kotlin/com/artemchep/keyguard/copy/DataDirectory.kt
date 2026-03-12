package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.useBufferedSink
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.platform.util.isRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.io.Sink
import net.harawata.appdirs.AppDirsFactory
import org.kodein.di.DirectDI
import java.io.File

class DataDirectory(
) : DirsService {
    companion object {
        private val APP_NAME = if (isRelease) "keyguard" else "keyguard-dev"
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

    fun cache(): IO<String> = ioEffect(Dispatchers.IO) { cacheBlocking() }

    fun cacheBlocking(): String = run {
        val appDirs = AppDirsFactory.getInstance()
        appDirs.getUserCacheDir(APP_NAME, null, APP_AUTHOR)
    }

    fun downloads(): IO<String> = ioEffect(Dispatchers.IO) { downloadsBlocking() }

    fun downloadsBlocking(): String = kotlin.run {
        val appDirs = AppDirsFactory.getInstance()
        appDirs.getUserDownloadsDir(APP_NAME, null, APP_AUTHOR)
    }

    override fun saveToDownloads(
        fileName: String,
        write: suspend (Sink) -> Unit,
    ): IO<String?> = ioEffect {
        val downloadsDir = downloads()
            .bind()
            .let(::File)
        val file = downloadsDir.resolve(fileName)
        // Ensure the parent directory does exist
        // before writing the file.
        file.parentFile?.mkdirs()
        file.outputStream().useBufferedSink(write)
        file.toURI().toString()
    }
}
