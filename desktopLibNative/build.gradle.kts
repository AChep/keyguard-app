import binaries.SignAndCopyBinaryTask
import binaries.detectHostPlatform

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

val hostPlatform = detectHostPlatform()
val desktopLibBinaryName = if (hostPlatform.isWindows) "keyguard-lib.dll" else "keyguard-lib"

tasks.register<SignAndCopyBinaryTask>(Tasks.compileNativeUniversal) {
    dependsOn(tasks.named(hostPlatform.desktopLibNativeLinkTaskName))

    // Set Inputs
    // We use layout.buildDirectory to be Configuration Cache friendly
    val sourcePath = "bin/${hostPlatform.desktopLibNativeBinaryPath}"
    sourceBinary.set(layout.buildDirectory.file(sourcePath))
    
    // Set Outputs
    destinationBinary.set(layout.buildDirectory.file("bin/${hostPlatform.composeResourceDir}/$desktopLibBinaryName"))

    // Set Properties
    platformMacOs.set(hostPlatform.isMacOs)
    platformWindows.set(hostPlatform.isWindows)
    markExecutable.set(true)
    val macOsCertIdentity = findProperty("cert_identity") as String?
    certIdentity.set(macOsCertIdentity)
}
