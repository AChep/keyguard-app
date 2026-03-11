package com.artemchep.jna

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.isExecutable

private const val BINARY_FILE_NAME_UNIX = "keyguard-lib"
private const val BINARY_FILE_NAME_WINDOWS = "keyguard-lib.dll"

private val binaryFileName: String
    get() = if (Platform.isWindows() || Platform.isWindowsCE()) {
        BINARY_FILE_NAME_WINDOWS
    } else {
        BINARY_FILE_NAME_UNIX
    }

private val libraryFile by lazy {
    findLibBinaryFile()
}

/**
 * Attempts to locate the keyguard-lib binary.
 */
internal fun findLibBinaryPathOrNull(): java.nio.file.Path? {
    val appDirProp = System.getProperty("compose.application.resources.dir")
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val appDirPath = Path(appDirProp)

    val binaryName = binaryFileName
    val binaryPath = appDirPath.resolve(binaryName)
    if (binaryPath.isExecutable()) {
        return binaryPath
    }
    return null
}

private fun findLibBinaryFile(): File {
    val path = findLibBinaryPathOrNull()
    if (path != null) {
        return path.toFile()
    }

    val errorMessage = "Could not locate native desktop lib binary in app resources"
    throw IllegalStateException(errorMessage)
}

public interface DesktopLibJna : Library {
    public companion object {
        @Volatile
        private var instance: DesktopLibJna? = null

        public fun get(): DesktopLibJna {
            if (instance == null) {
                synchronized(DesktopLibJna::class.java) {
                    if (instance == null) {
                        instance = Native.load(
                            libraryFile.canonicalPath,
                            DesktopLibJna::class.java,
                        ) as DesktopLibJna
                    }
                }
            }
            return instance!!
        }
    }

    public fun autoType(payload: Pointer): Boolean

    // Biometrics

    public fun biometricsIsSupported(): Boolean

    public fun biometricsVerify(
        title: Pointer,
        callback: BiometricsVerifyCallback,
    )

    public interface BiometricsVerifyCallback : Callback {
        public fun invoke(success: Boolean, error: Pointer?)
    }


    // Keychain

    public fun keychainAddPassword(id: Pointer, password: Pointer): Boolean

    public fun keychainGetPassword(id: Pointer): Pointer?

    public fun keychainDeletePassword(id: Pointer): Boolean

    public fun keychainContainsPassword(id: Pointer): Boolean

    // Notification

    public fun postNotification(
        id: Int,
        title: Pointer,
        text: Pointer
    ): Int

    // Global hotkeys

    public fun registerNativeGlobalHotKey(
        nativeKeyCode: Int,
        nativeModifiers: Int,
        callback: GlobalHotKeyCallback,
    ): Int

    public fun unregisterNativeGlobalHotKey(id: Int): Boolean

    public interface GlobalHotKeyCallback : Callback {
        public fun invoke(id: Int)
    }

    // Other

    /** Frees given pointer */
    public fun freePointer(ptr: Pointer)
}
