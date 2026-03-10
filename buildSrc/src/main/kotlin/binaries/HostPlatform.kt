package binaries

enum class HostPlatform(
    val composeResourceDir: String,
    val sshAgentRustTarget: String,
    val desktopLibRustTarget: String,
    val isMacOs: Boolean,
    val isWindows: Boolean,
) {
    LinuxX64(
        composeResourceDir = "linux-x64",
        sshAgentRustTarget = "x86_64-unknown-linux-gnu",
        desktopLibRustTarget = "x86_64-unknown-linux-gnu",
        isMacOs = false,
        isWindows = false,
    ),
    LinuxArm64(
        composeResourceDir = "linux-arm64",
        sshAgentRustTarget = "aarch64-unknown-linux-gnu",
        desktopLibRustTarget = "aarch64-unknown-linux-gnu",
        isMacOs = false,
        isWindows = false,
    ),
    MacosX64(
        composeResourceDir = "macos-x64",
        sshAgentRustTarget = "x86_64-apple-darwin",
        desktopLibRustTarget = "x86_64-apple-darwin",
        isMacOs = true,
        isWindows = false,
    ),
    MacosArm64(
        composeResourceDir = "macos-arm64",
        sshAgentRustTarget = "aarch64-apple-darwin",
        desktopLibRustTarget = "aarch64-apple-darwin",
        isMacOs = true,
        isWindows = false,
    ),
    WindowsX64(
        composeResourceDir = "windows-x64",
        sshAgentRustTarget = "x86_64-pc-windows-msvc",
        desktopLibRustTarget = "x86_64-pc-windows-msvc",
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
            HostPlatform.WindowsX64

        else -> error("Unsupported host platform: osName=$osName, osArch=$osArch")
    }
}

fun HostPlatform.binaryName(base: String): String =
    if (isWindows) "$base.exe" else base

fun HostPlatform.dynamicLibraryName(base: String): String = when {
    isWindows -> "$base.dll"
    isMacOs -> "lib$base.dylib"
    else -> "lib$base.so"
}
