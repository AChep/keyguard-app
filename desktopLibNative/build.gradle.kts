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
        binPath = "mingwX64/releaseShared/libkeyguard.dll",
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

tasks.register(Tasks.compileNativeUniversal) {
    dependsOn(tasks.named(os.binTaskName))

    inputs.files("src/")
    val out = file("build/bin/")
    outputs.dir(out)

    doFirst {
        val bin = file("build/bin/${os.binPath}")
        require(bin.exists()) {
            "Native binary must exist before universal " +
                    "compile task is started!"
        }

        bin.copyTo(
            target = out.resolve("universal/libkeyguard"),
            overwrite = true,
        )
    }
}
