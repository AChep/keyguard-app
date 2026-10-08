package com.artemchep.keyguard.buildplugins.androidssh

import com.artemchep.keyguard.buildplugins.cargo.HostPlatform
import com.artemchep.keyguard.buildplugins.cargo.binaryName
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform
import org.gradle.api.GradleException
import java.io.File
import java.util.Properties

object AndroidCargoEnvironment {
    private val linkerExecutablePrefixes = mapOf(
        "aarch64-linux-android" to "aarch64-linux-android",
        "armv7-linux-androideabi" to "armv7a-linux-androideabi",
        "i686-linux-android" to "i686-linux-android",
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

    data class TargetBuildTools(
        val toolchain: Toolchain,
        val cCompiler: File,
        val cxxCompiler: File,
        val archiver: File,
        val ranlib: File,
    )

    /**
     * Resolves the SDK in the same order as AGP, so Cargo links against the NDK of the SDK
     * that Gradle builds with: `sdk.dir` in local.properties wins over the environment.
     */
    fun resolveAndroidSdkRoot(
        rootDir: File,
        localPropertiesFile: File?,
        environment: (String) -> String? = System::getenv,
    ): SdkResolution {
        val effectiveLocalPropertiesFile = localPropertiesFile ?: File(rootDir, "local.properties")
        val sdkRoot = sequenceOf(
            effectiveLocalPropertiesFile.takeIf(File::isFile)?.let(::readSdkDirFromLocalProperties),
            environment("ANDROID_HOME"),
            environment("ANDROID_SDK_ROOT"),
        )
            .filterNotNull()
            .filter(String::isNotBlank)
            .map { path -> File(path).takeIf(File::isAbsolute) ?: File(rootDir, path) }
            .firstOrNull(File::isDirectory)
        return SdkResolution(
            sdkRoot = sdkRoot,
            localPropertiesPath = effectiveLocalPropertiesFile.absolutePath,
        )
    }

    fun resolveNdkDirectory(
        sdkRoot: File,
        ndkVersion: String,
    ): File? = File(sdkRoot, "ndk/$ndkVersion")
        .takeIf(File::isDirectory)

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
        ndkVersion: String,
    ): Toolchain {
        val sdkResolution = resolveAndroidSdkRoot(
            rootDir = rootDir,
            localPropertiesFile = localPropertiesFile,
        )
        val sdkRoot = sdkResolution.sdkRoot ?: throw GradleException(
            buildString {
                appendLine("Android SDK root could not be resolved.")
                appendLine("Checked, in order:")
                appendLine("  ${sdkResolution.localPropertiesPath} (sdk.dir)")
                appendLine("  ANDROID_HOME")
                appendLine("  ANDROID_SDK_ROOT")
                append("Set sdk.dir in local.properties or the ANDROID_HOME environment variable.")
            },
        )

        val ndkDir = resolveNdkDirectory(
            sdkRoot = sdkRoot,
            ndkVersion = ndkVersion,
        ) ?: throw GradleException(
            buildString {
                appendLine("Pinned Android NDK $ndkVersion could not be found.")
                appendLine("Resolved Android SDK root: ${sdkRoot.absolutePath}")
                append("Expected: ${File(sdkRoot, "ndk/$ndkVersion").absolutePath}")
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
        ndkVersion: String,
    ): File {
        return resolveTargetBuildTools(
            rootDir = rootDir,
            localPropertiesFile = localPropertiesFile,
            rustTarget = rustTarget,
            androidApiLevel = androidApiLevel,
            ndkVersion = ndkVersion,
        ).cCompiler
    }

    fun resolveTargetBuildTools(
        rootDir: File,
        localPropertiesFile: File?,
        rustTarget: String,
        androidApiLevel: Int,
        ndkVersion: String,
    ): TargetBuildTools {
        val toolchain = resolveToolchain(
            rootDir = rootDir,
            localPropertiesFile = localPropertiesFile,
            ndkVersion = ndkVersion,
        )
        val cCompiler = File(
            toolchain.toolchainBinDir,
            androidCompilerExecutableName(
                rustTarget = rustTarget,
                androidApiLevel = androidApiLevel,
                cxx = false,
            ),
        )
        val cxxCompiler = File(
            toolchain.toolchainBinDir,
            androidCompilerExecutableName(
                rustTarget = rustTarget,
                androidApiLevel = androidApiLevel,
                cxx = true,
            ),
        )
        val archiver = File(toolchain.toolchainBinDir, llvmToolExecutableName("llvm-ar"))
        val ranlib = File(toolchain.toolchainBinDir, llvmToolExecutableName("llvm-ranlib"))

        listOf(
            "C compiler" to cCompiler,
            "C++ compiler" to cxxCompiler,
            "archiver" to archiver,
            "ranlib" to ranlib,
        ).forEach { (toolName, executable) ->
            if (!executable.isFile) {
                throw GradleException(
                    buildString {
                        appendLine("Android NDK $toolName binary is missing.")
                        appendLine("Resolved Android SDK root: ${toolchain.sdkRoot.absolutePath}")
                        appendLine("Resolved Android NDK: ${toolchain.ndkDir.absolutePath}")
                        appendLine("Checked directory: ${toolchain.toolchainBinDir.absolutePath}")
                        append("Expected executable: ${executable.absolutePath}")
                    },
                )
            }
        }

        return TargetBuildTools(
            toolchain = toolchain,
            cCompiler = cCompiler,
            cxxCompiler = cxxCompiler,
            archiver = archiver,
            ranlib = ranlib,
        )
    }

    fun linkerEnvironmentName(rustTarget: String): String =
        "CARGO_TARGET_${rustTarget.uppercase().replace('-', '_').replace('.', '_')}_LINKER"

    fun rustFlagsEnvironmentName(rustTarget: String): String =
        "CARGO_TARGET_${rustTarget.uppercase().replace('-', '_').replace('.', '_')}_RUSTFLAGS"

    fun targetEnvironmentName(
        variable: String,
        rustTarget: String,
    ): String = "${variable}_${rustTarget.lowercase().replace('-', '_').replace('.', '_')}"

    fun resolveReadElfExecutable(
        rootDir: File,
        localPropertiesFile: File?,
        ndkVersion: String,
    ): File {
        val toolchain = resolveToolchain(
            rootDir = rootDir,
            localPropertiesFile = localPropertiesFile,
            ndkVersion = ndkVersion,
        )
        val executable = File(toolchain.toolchainBinDir, llvmToolExecutableName("llvm-readelf"))
        if (!executable.isFile) {
            throw GradleException(
                "Android NDK llvm-readelf is missing: ${executable.absolutePath}",
            )
        }
        return executable
    }

    private fun readSdkDirFromLocalProperties(file: File): String? {
        val properties = Properties()
        file.inputStream().use(properties::load)
        return properties.getProperty("sdk.dir")
    }

    private fun hostToolchainDirectoryCandidates(): List<String> = when (detectHostPlatform()) {
        HostPlatform.MacosArm64 -> listOf("darwin-arm64", "darwin-x86_64")
        HostPlatform.MacosX64 -> listOf("darwin-x86_64", "darwin-arm64")
        HostPlatform.LinuxX64, HostPlatform.LinuxArm64 -> listOf("linux-x86_64")
        HostPlatform.WindowsX64, HostPlatform.WindowsArm64 -> listOf("windows-x86_64")
    }

    private fun androidCompilerExecutableName(
        rustTarget: String,
        androidApiLevel: Int,
        cxx: Boolean,
    ): String {
        val executablePrefix = linkerExecutablePrefixes[rustTarget]
            ?: throw GradleException("Unsupported Android target: $rustTarget")
        val executableName = buildString {
            append(executablePrefix)
            append(androidApiLevel)
            append("-clang")
            if (cxx) append("++")
        }
        return if (isWindowsHost()) {
            "$executableName.cmd"
        } else {
            executableName
        }
    }

    private fun llvmToolExecutableName(name: String): String =
        detectHostPlatform().binaryName(name)

    private fun isWindowsHost(): Boolean = detectHostPlatform().isWindows
}
