import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Useful Kotlin tutorial
// https://kotlinlang.org/docs/native-dynamic-libraries.html#create-a-kotlin-library
kotlin {
    explicitApi()

    listOf(
        macosX64(),
        macosArm64(),
        linuxX64(),
        linuxArm64(),
        mingwX64(),
    ).forEach { target ->
        target.binaries.sharedLib {
            baseName = "keyguard"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val macosMain by creating {
            dependsOn(commonMain)
        }
        val macosX64Main by getting {
            dependsOn(macosMain)
        }
        val macosArm64Main by getting {
            dependsOn(macosMain)
        }
        val linuxMain by creating {
            dependsOn(commonMain)
        }
        val linuxX64Main by getting {
            dependsOn(linuxMain)
        }
        val linuxArm64Main by getting {
            dependsOn(linuxMain)
        }
        val mingwMain by creating {
            dependsOn(commonMain)
        }
        val mingwX64Main by getting {
            dependsOn(mingwMain)
        }
    }
}

enum class Os(
    val binPath: String,
    val binTaskName: String,
) {
    LINUX_X64(
        binPath = "linuxX64/releaseShared/libkeyguard.so",
        binTaskName = "linkReleaseSharedLinuxX64",
    ),
    LINUX_ARM64(
        binPath = "linuxArm64/releaseShared/libkeyguard.so",
        binTaskName = "linkReleaseSharedLinuxArm64",
    ),
    MAC_X64(
        binPath = "macosX64/releaseShared/libkeyguard.dylib",
        binTaskName = "linkReleaseSharedMacosX64",
    ),
    MAC_ARM64(
        binPath = "macosArm64/releaseShared/libkeyguard.dylib",
        binTaskName = "linkReleaseSharedMacosArm64",
    ),
    WIN_X64(
        binPath = "mingwX64/releaseShared/keyguard.dll",
        binTaskName = "linkReleaseSharedMingwX64",
    ),
}

fun detectOs(): Os {
    val osArch: String = System.getProperty("os.arch")
    val osName: String = System.getProperty("os.name")

    val isArm = osArch.contains("aarch", ignoreCase = true) ||
            osArch.contains("arm", ignoreCase = true)
    return when {
        osName.startsWith("Linux") -> {
            if (isArm) Os.LINUX_ARM64 else Os.LINUX_X64
        }

        osName.startsWith("Mac") ||
                osName.startsWith("Darwin") -> {
            if (isArm) Os.MAC_ARM64 else Os.MAC_X64
        }

        osName.startsWith("Windows") -> {
            Os.WIN_X64
        }

        else -> {
            // Unsupported OS, we do not provide binaries for
            // this. Ideally in this case we skip building
            // native libraries and use just JVM functionality.
            throw IllegalStateException("Unknown OS type: arch=$osArch, name=$osName")
        }
    }
}

val os = detectOs()

abstract class SignAndCopyBinary : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFile
    abstract val sourceBinary: RegularFileProperty

    @get:OutputFile
    abstract val destinationBinary: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val certIdentity: Property<String>

    @get:Input
    abstract val platformMacOs: Property<Boolean>

    @TaskAction
    fun action() {
        val bin = sourceBinary.get().asFile
        val identity = certIdentity.orNull

        // Validation happens automatically via @InputFile, 
        // but we can double check
        require(bin.exists()) { 
            "Native binary must exist before universal compile task is started!" 
        }

        // Sign the binary if the platform 
        // requires it.
        if (platformMacOs.get() && identity != null) {
            logger.lifecycle("Signing native lib binary with identity ${identity.take(2)}****")
            execOperations.exec {
                commandLine(
                    "codesign",
                    "--force",
                    "--options", "runtime",
                    "--sign", identity,
                    bin.absolutePath,
                )
            }
        }

        bin.copyTo(
            target = destinationBinary.get().asFile,
            overwrite = true
        )
    }
}

tasks.register<SignAndCopyBinary>(Tasks.compileNativeUniversal) {
    dependsOn(tasks.named(os.binTaskName))

    // Set Inputs
    // We use layout.buildDirectory to be Configuration Cache friendly
    val sourcePath = "bin/${os.binPath}"
    sourceBinary.set(layout.buildDirectory.file(sourcePath))
    
    // Set Outputs
    destinationBinary.set(layout.buildDirectory.file("bin/universal/libkeyguard"))

    // Set Properties
    val macOsDetected = os == Os.MAC_ARM64 || os == Os.MAC_X64
    platformMacOs.set(macOsDetected)
    val macOsCertIdentity = findProperty("cert_identity") as String?
    certIdentity.set(macOsCertIdentity)
}
