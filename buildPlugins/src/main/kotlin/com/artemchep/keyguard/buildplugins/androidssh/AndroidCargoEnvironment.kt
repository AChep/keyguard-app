package com.artemchep.keyguard.buildplugins.androidssh

import org.gradle.api.GradleException
import java.io.File
import java.util.Properties

object AndroidCargoEnvironment {
    private val linkerExecutablePrefixes = mapOf(
        "aarch64-linux-android" to "aarch64-linux-android",
        "armv7-linux-androideabi" to "armv7a-linux-androideabi",
        "x86_64-linux-android" to "x86_64-linux-android",
    )

    data class SdkResolution(
        val sdkRoot: File?,
        val localPropertiesPath: String,
    )

    data class Toolchain(
        val sdkRoot: File,
        val ndkDir: File,
        val toolchainBinDir: File,
    )

    fun resolveAndroidSdkRoot(
        rootDir: File,
        localPropertiesFile: File?,
    ): SdkResolution {
        val effectiveLocalPropertiesFile = localPropertiesFile ?: File(rootDir, "local.properties")
        val localPropertiesPath = effectiveLocalPropertiesFile.absolutePath

        val fromAndroidSdkRoot = System.getenv("ANDROID_SDK_ROOT")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::isDirectory)
        if (fromAndroidSdkRoot != null) {
            return SdkResolution(
                sdkRoot = fromAndroidSdkRoot,
                localPropertiesPath = localPropertiesPath,
            )
        }

        val fromAndroidHome = System.getenv("ANDROID_HOME")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::isDirectory)
        if (fromAndroidHome != null) {
            return SdkResolution(
                sdkRoot = fromAndroidHome,
                localPropertiesPath = localPropertiesPath,
            )
        }

        val fromLocalProperties = effectiveLocalPropertiesFile
            .takeIf(File::isFile)
            ?.let(::readSdkDirFromLocalProperties)
            ?.takeIf(File::isDirectory)

        return SdkResolution(
            sdkRoot = fromLocalProperties,
            localPropertiesPath = localPropertiesPath,
        )
    }

    fun resolveNdkDirectory(sdkRoot: File): File? {
        val ndkRoot = File(sdkRoot, "ndk")
        val versionedNdk = ndkRoot.listFiles()
            ?.filter(File::isDirectory)
            ?.maxWithOrNull(::compareVersionDirectories)
        if (versionedNdk != null) {
            return versionedNdk
        }

        return File(sdkRoot, "ndk-bundle")
            .takeIf(File::isDirectory)
    }

    fun hostToolchainBinDir(ndkDir: File): File {
        val candidates = hostToolchainDirectoryCandidates()
        return candidates.asSequence()
            .map { candidate -> File(ndkDir, "toolchains/llvm/prebuilt/$candidate/bin") }
            .firstOrNull(File::isDirectory)
            ?: File(ndkDir, "toolchains/llvm/prebuilt/${candidates.first()}/bin")
    }

    fun resolveToolchain(
        rootDir: File,
        localPropertiesFile: File?,
    ): Toolchain {
        val sdkResolution = resolveAndroidSdkRoot(
            rootDir = rootDir,
            localPropertiesFile = localPropertiesFile,
        )
        val sdkRoot = sdkResolution.sdkRoot ?: throw GradleException(
            buildString {
                appendLine("Android SDK root could not be resolved.")
                appendLine("Checked, in order:")
                appendLine("  ANDROID_SDK_ROOT")
                appendLine("  ANDROID_HOME")
                appendLine("  ${sdkResolution.localPropertiesPath} (sdk.dir)")
                append("Set ANDROID_SDK_ROOT or ensure local.properties contains sdk.dir.")
            },
        )

        val ndkDir = resolveNdkDirectory(sdkRoot) ?: throw GradleException(
            buildString {
                appendLine("Android NDK could not be found.")
                appendLine("Resolved Android SDK root: ${sdkRoot.absolutePath}")
                appendLine("Checked:")
                appendLine("  ${File(sdkRoot, "ndk").absolutePath}")
                append("  ${File(sdkRoot, "ndk-bundle").absolutePath}")
            },
        )

        val toolchainBinDir = hostToolchainBinDir(ndkDir)
        if (!toolchainBinDir.isDirectory) {
            throw GradleException(
                buildString {
                    appendLine("Android NDK host toolchain directory is missing.")
                    appendLine("Resolved Android SDK root: ${sdkRoot.absolutePath}")
                    appendLine("Resolved Android NDK: ${ndkDir.absolutePath}")
                    append("Expected toolchain directory: ${toolchainBinDir.absolutePath}")
                },
            )
        }

        return Toolchain(
            sdkRoot = sdkRoot,
            ndkDir = ndkDir,
            toolchainBinDir = toolchainBinDir,
        )
    }

    fun resolveLinkerExecutable(
        rootDir: File,
        localPropertiesFile: File?,
        rustTarget: String,
        androidApiLevel: Int,
    ): File {
        val toolchain = resolveToolchain(
            rootDir = rootDir,
            localPropertiesFile = localPropertiesFile,
        )
        val linker = File(
            toolchain.toolchainBinDir,
            androidLinkerExecutableName(
                rustTarget = rustTarget,
                androidApiLevel = androidApiLevel,
            ),
        )
        if (!linker.isFile) {
            throw GradleException(
                buildString {
                    appendLine("Android NDK linker binary is missing.")
                    appendLine("Resolved Android SDK root: ${toolchain.sdkRoot.absolutePath}")
                    appendLine("Resolved Android NDK: ${toolchain.ndkDir.absolutePath}")
                    appendLine("Checked directory: ${toolchain.toolchainBinDir.absolutePath}")
                    append("Expected linker: ${linker.absolutePath}")
                },
            )
        }

        return linker
    }

    fun linkerEnvironmentName(rustTarget: String): String =
        "CARGO_TARGET_${rustTarget.uppercase().replace('-', '_').replace('.', '_')}_LINKER"

    private fun readSdkDirFromLocalProperties(file: File): File? {
        val properties = Properties()
        file.inputStream().use(properties::load)
        return properties.getProperty("sdk.dir")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
    }

    private fun compareVersionDirectories(left: File, right: File): Int {
        val leftParts = versionParts(left.name)
        val rightParts = versionParts(right.name)
        val maxSize = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until maxSize) {
            val leftPart = leftParts.getOrElse(index) { 0 }
            val rightPart = rightParts.getOrElse(index) { 0 }
            if (leftPart != rightPart) {
                return leftPart.compareTo(rightPart)
            }
        }
        return left.name.compareTo(right.name)
    }

    private fun versionParts(version: String): List<Int> =
        version.split('.', '-', '_')
            .mapNotNull { part -> part.toIntOrNull() }

    private fun hostToolchainDirectoryCandidates(): List<String> {
        val osName = System.getProperty("os.name")
        val osArch = System.getProperty("os.arch")
        return when {
            osName.startsWith("Mac", ignoreCase = true) ||
                osName.startsWith("Darwin", ignoreCase = true) -> if (
                osArch.equals("aarch64", ignoreCase = true) ||
                osArch.equals("arm64", ignoreCase = true)
            ) {
                listOf("darwin-arm64", "darwin-x86_64")
            } else {
                listOf("darwin-x86_64", "darwin-arm64")
            }

            osName.startsWith("Linux", ignoreCase = true) -> listOf("linux-x86_64")
            osName.startsWith("Windows", ignoreCase = true) -> listOf("windows-x86_64")
            else -> throw GradleException("Unsupported host platform for Android NDK validation: $osName")
        }
    }

    private fun androidLinkerExecutableName(
        rustTarget: String,
        androidApiLevel: Int,
    ): String {
        val executablePrefix = linkerExecutablePrefixes[rustTarget]
            ?: throw GradleException("Unsupported Android target: $rustTarget")
        val executableName = "$executablePrefix${androidApiLevel}-clang"
        return if (isWindowsHost()) {
            "$executableName.cmd"
        } else {
            executableName
        }
    }

    private fun isWindowsHost(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
