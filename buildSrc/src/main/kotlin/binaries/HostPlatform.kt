package binaries

enum class HostPlatform(
    val composeResourceDir: String,
    val sshAgentRustTarget: String,
    val desktopLibNativeLinkTaskName: String,
    val desktopLibNativeBinaryPath: String,
    val isMacOs: Boolean,
    val isWindows: Boolean,
) {
    LinuxX64(
        composeResourceDir = "linux-x64",
        sshAgentRustTarget = "x86_64-unknown-linux-gnu",
        desktopLibNativeLinkTaskName = "linkReleaseSharedLinuxX64",
        desktopLibNativeBinaryPath = "linuxX64/releaseShared/libkeyguard.so",
        isMacOs = false,
        isWindows = false,
    ),
    LinuxArm64(
        composeResourceDir = "linux-arm64",
        sshAgentRustTarget = "aarch64-unknown-linux-gnu",
        desktopLibNativeLinkTaskName = "linkReleaseSharedLinuxArm64",
        desktopLibNativeBinaryPath = "linuxArm64/releaseShared/libkeyguard.so",
        isMacOs = false,
        isWindows = false,
    ),
    MacosX64(
        composeResourceDir = "macos-x64",
        sshAgentRustTarget = "x86_64-apple-darwin",
        desktopLibNativeLinkTaskName = "linkReleaseSharedMacosX64",
        desktopLibNativeBinaryPath = "macosX64/releaseShared/libkeyguard.dylib",
        isMacOs = true,
        isWindows = false,
    ),
    MacosArm64(
        composeResourceDir = "macos-arm64",
        sshAgentRustTarget = "aarch64-apple-darwin",
        desktopLibNativeLinkTaskName = "linkReleaseSharedMacosArm64",
        desktopLibNativeBinaryPath = "macosArm64/releaseShared/libkeyguard.dylib",
        isMacOs = true,
        isWindows = false,
    ),
    WindowsX64(
        composeResourceDir = "windows-x64",
        sshAgentRustTarget = "x86_64-pc-windows-msvc",
        desktopLibNativeLinkTaskName = "linkReleaseSharedMingwX64",
        desktopLibNativeBinaryPath = "mingwX64/releaseShared/keyguard.dll",
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
