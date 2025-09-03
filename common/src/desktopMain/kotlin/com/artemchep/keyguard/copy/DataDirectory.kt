package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.dirs.DirsService
import kotlinx.coroutines.Dispatchers
import net.harawata.appdirs.AppDirsFactory
import org.kodein.di.DirectDI
import java.io.File
import java.io.OutputStream

class DataDirectory(
) : DirsService {
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
        write: suspend (OutputStream) -> Unit,
    ): IO<String?> = ioEffect {
        val downloadsDir = downloads()
            .bind()
            .let(::File)
        val file = downloadsDir.resolve(fileName)
        // Ensure the parent directory does exist
        // before writing the file.
        file.parentFile?.mkdirs()
        file.outputStream()
            .use {
                write(it)
            }
        file.toURI().toString()
    }
}
