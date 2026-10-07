package com.artemchep.keyguard.buildplugins.cargo

enum class HostPlatform(
    val composeResourceDir: String,
    val rustTarget: String,
    val isMacOs: Boolean,
    val isWindows: Boolean,
) {
    LinuxX64(
        composeResourceDir = "linux-x64",
        rustTarget = "x86_64-unknown-linux-gnu",
        isMacOs = false,
        isWindows = false,
    ),
    LinuxArm64(
        composeResourceDir = "linux-arm64",
        rustTarget = "aarch64-unknown-linux-gnu",
        isMacOs = false,
        isWindows = false,
    ),
    MacosX64(
        composeResourceDir = "macos-x64",
        rustTarget = "x86_64-apple-darwin",
        isMacOs = true,
        isWindows = false,
    ),
    MacosArm64(
        composeResourceDir = "macos-arm64",
        rustTarget = "aarch64-apple-darwin",
        isMacOs = true,
        isWindows = false,
    ),
    WindowsX64(
        composeResourceDir = "windows-x64",
        rustTarget = "x86_64-pc-windows-msvc",
        isMacOs = false,
        isWindows = true,
    ),
    WindowsArm64(
        composeResourceDir = "windows-arm64",
        rustTarget = "aarch64-pc-windows-msvc",
        isMacOs = false,
        isWindows = true,
    ),
}

fun detectHostPlatform(
    osName: String = System.getProperty("os.name"),
    osArch: String = System.getProperty("os.arch"),
): HostPlatform {
    val arch = osArch.lowercase()
    val isArm = arch.contains("aarch") || arch.contains("arm")
    return when {
        osName.startsWith("Linux", ignoreCase = true) ->
            if (isArm) HostPlatform.LinuxArm64 else HostPlatform.LinuxX64

        osName.startsWith("Mac", ignoreCase = true) ||
            osName.startsWith("Darwin", ignoreCase = true) ->
            if (isArm) HostPlatform.MacosArm64 else HostPlatform.MacosX64

        osName.startsWith("Windows", ignoreCase = true) ->
            if (isArm) HostPlatform.WindowsArm64 else HostPlatform.WindowsX64

        else -> error("Unsupported host platform: osName=$osName, osArch=$osArch")
    }
}

val HostPlatform.isLinux: Boolean
    get() = !isMacOs && !isWindows

/** The `<os>-<arch>` suffix of the desktop tarball. Release jobs match `*-linux-<arch>.tar.gz`. */
val HostPlatform.tarballSuffix: String
    get() = when (this) {
        HostPlatform.LinuxX64 -> "linux-x86_64"
        HostPlatform.LinuxArm64 -> "linux-aarch64"
        HostPlatform.MacosX64 -> "macosx-x86_64"
        HostPlatform.MacosArm64 -> "macosx-aarch64"
        HostPlatform.WindowsX64 -> "windows-x86_64"
        HostPlatform.WindowsArm64 -> "windows-aarch64"
    }

/** The MSIX `ProcessorArchitecture` of a Windows host. */
val HostPlatform.msixArchitecture: String
    get() = when (this) {
        HostPlatform.WindowsX64 -> "x64"
        HostPlatform.WindowsArm64 -> "arm64"
        else -> error("MSIX packages are only built on Windows, not on $this")
    }

fun HostPlatform.binaryName(base: String): String =
    if (isWindows) "$base.exe" else base

fun HostPlatform.dynamicLibraryName(base: String): String = when {
    isWindows -> "$base.dll"
    isMacOs -> "lib$base.dylib"
    else -> "lib$base.so"
}
