package com.artemchep.desktop

/** One file of a drag: its name in the destination folder and its exact size in bytes. */
public class WindowsVirtualFileEntry(public val name: String, public val size: Long)

/** Callbacks retained by the native OLE offer until it retires. */
public interface WindowsVirtualFileCallbacks : NativeDragCallbacks {
    /** Opens an independent positional reader for file [index]. Called only on the native OLE thread. */
    public fun openReader(index: Int): WindowsVirtualFileReader
}

/** A blocking positional reader owned and closed by one native stream. */
public interface WindowsVirtualFileReader : AutoCloseable {
    /** Reads up to [length] bytes at [offset] into data[0, length). Returns the count; 0 means EOF. */
    public fun readAt(offset: Long, data: ByteArray, length: Int): Int

    override fun close()
}

/** Bump with `ABI_VERSION` in virtual_file/java.rs for an incompatible change. */
internal const val WINDOWS_VIRTUAL_FILE_ABI_VERSION = 2

/**
 * Starts one OLE virtual-file drag carrying [files] (1..64, names unique ignoring case),
 * without a producer file path. Once revoked, the offer retires when its streams are released.
 * Null means native did not take ownership and no callback will run.
 */
public fun startWindowsVirtualFiles(
    files: List<WindowsVirtualFileEntry>,
    callbacks: WindowsVirtualFileCallbacks,
): NativeDragSession? {
    val snapshot = files.toList()
    require(isValidWindowsVirtualFileSet(snapshot))
    windowsVirtualFileLibrary.ensureLoaded()
    val handle = WindowsVirtualFileJni.start(
        names = Array(snapshot.size) { snapshot[it].name },
        sizes = LongArray(snapshot.size) { snapshot[it].size },
        callbacks = callbacks,
    )
    return if (handle != 0L) NativeDragHandle(handle, WindowsVirtualFileJni::revoke) else null
}

/**
 * Whether one drag can carry [files]: 1..[NATIVE_DRAG_MAX_FILES] of them, each with a valid name
 * and a non-negative size, and no two names equal ignoring case. Mirrors the native check.
 */
internal fun isValidWindowsVirtualFileSet(files: List<WindowsVirtualFileEntry>): Boolean =
    files.size in 1..NATIVE_DRAG_MAX_FILES &&
        files.all { it.size >= 0L && isValidWindowsVirtualFileName(it.name) } &&
        // Locale-independent uppercase also joins a few names Windows keeps apart, such as "ß" and "SS".
        files.distinctBy { it.name.uppercase() }.size == files.size

/** `FILEDESCRIPTORW.cFileName` holds `MAX_PATH` UTF-16 units, including the terminator. */
private const val WINDOWS_NAME_MAX_LENGTH = 259

private const val WINDOWS_NAME_FORBIDDEN = "<>:\"/\\|?*"

/** Device names Windows reserves, with or without an extension. */
private val windowsReservedStems: Set<String> = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"))
    val suffixes = ('0'..'9').map(Char::toString) + listOf("\u00b9", "\u00b2", "\u00b3")
    for (prefix in listOf("COM", "LPT")) {
        suffixes.forEach { add(prefix + it) }
    }
}

/** Whether Explorer can create [name] as one path component. Mirrors the native check. */
internal fun isValidWindowsVirtualFileName(name: String): Boolean =
    name.isNotEmpty() &&
        name.length <= WINDOWS_NAME_MAX_LENGTH &&
        name.last() != ' ' &&
        name.last() != '.' &&
        name.none { it < ' ' || it in WINDOWS_NAME_FORBIDDEN } &&
        // Rejects unpaired surrogates.
        Charsets.UTF_8.newEncoder().canEncode(name) &&
        name.substringBefore('.').trimEnd(' ').asciiUppercase() !in windowsReservedStems

/** Windows compares device names without locale rules. */
private fun String.asciiUppercase(): String = buildString(length) {
    this@asciiUppercase.forEach { char ->
        append(if (char in 'a'..'z') char.uppercaseChar() else char)
    }
}

private val windowsVirtualFileLibrary = nativeDragLibrary(
    platform = "Windows",
    expectedAbiVersion = WINDOWS_VIRTUAL_FILE_ABI_VERSION,
    abiVersion = WindowsVirtualFileJni::abiVersion,
)

internal object WindowsVirtualFileJni {
    external fun abiVersion(): Int

    /** Returns the offer's handle, or 0 when native did not take ownership. */
    external fun start(names: Array<String>, sizes: LongArray, callbacks: WindowsVirtualFileCallbacks): Long

    /** Ignores unknown and retired handles. */
    external fun revoke(handle: Long)
}
