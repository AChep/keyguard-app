package com.artemchep.keyguard.buildplugins.cargo

import org.junit.Assert.assertEquals
import org.junit.Test

class HostPlatformTest {
    @Test
    fun `preserves host targets and native artifact names`() {
        data class Expected(
            val osName: String,
            val osArch: String,
            val platform: HostPlatform,
            val target: String,
            val binary: String,
            val library: String,
        )
        listOf(
            Expected("Linux", "amd64", HostPlatform.LinuxX64, "x86_64-unknown-linux-gnu", "fixture", "libfixture.so"),
            Expected("Linux", "aarch64", HostPlatform.LinuxArm64, "aarch64-unknown-linux-gnu", "fixture", "libfixture.so"),
            Expected("Mac OS X", "x86_64", HostPlatform.MacosX64, "x86_64-apple-darwin", "fixture", "libfixture.dylib"),
            Expected("Darwin", "arm64", HostPlatform.MacosArm64, "aarch64-apple-darwin", "fixture", "libfixture.dylib"),
            Expected("Windows 11", "amd64", HostPlatform.WindowsX64, "x86_64-pc-windows-msvc", "fixture.exe", "fixture.dll"),
            Expected("Windows 11", "aarch64", HostPlatform.WindowsArm64, "aarch64-pc-windows-msvc", "fixture.exe", "fixture.dll"),
        ).forEach { expected ->
            val actual = detectHostPlatform(expected.osName, expected.osArch)
            assertEquals(expected.platform, actual)
            assertEquals(expected.target, actual.rustTarget)
            assertEquals(expected.binary, actual.binaryName("fixture"))
            assertEquals(expected.library, actual.dynamicLibraryName("fixture"))
        }
    }
}
