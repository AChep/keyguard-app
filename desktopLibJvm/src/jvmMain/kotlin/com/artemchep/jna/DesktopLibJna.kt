package com.artemchep.jna

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.nio.file.Files

private const val RES_FILENAME = "libkeyguard"

private const val OS_DIR_PREFIX = "libkeyguard"
private const val OS_FILE_FILENAME = "libkeyguard"

private val libraryFile by lazy {
    val filename = RES_FILENAME
    extractLibrary(filename)
}

private class Extractor

private fun extractLibrary(filename: String): File {
    val outDir = Files
        .createTempDirectory(OS_DIR_PREFIX)
        .toFile()
    val outFile = outDir
        .resolve(OS_FILE_FILENAME)
    outFile.deleteOnExit()
    // Copy the binary into the
    // output file.
    outFile.outputStream().use {
        Extractor::class.java.classLoader
            .getResourceAsStream(filename)!!
            .copyTo(it)
    }
    outFile.setExecutable(true, false)
    return outFile
}

public interface DesktopLibJna : Library {
    public companion object {
        @Volatile
        private var instance: DesktopLibJna? = null

        public fun get(): DesktopLibJna {
            if (instance == null) {
                synchronized(DesktopLibJna::class.java) {
                    if (instance == null) {
                        instance = Native.load(libraryFile.canonicalPath, DesktopLibJna::class.java) as DesktopLibJna
                    }
                }
            }
            return instance!!
        }
    }

    public fun autoType(payload: Pointer)
}
