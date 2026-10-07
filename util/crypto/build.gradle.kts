import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain
import com.artemchep.keyguard.buildplugins.testing.registerJvmBenchmark

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("keyguard.rust-multiplatform-library")
}

keyguardRust {
    extraSourceInputs.from(
        layout.projectDirectory.dir("schema"),
        rootProject.layout.projectDirectory.dir("thirdParty/rust"),
    )
    androidCmakeToolchainFile.set(layout.projectDirectory.file("cmake/android.toolchain.cmake"))
    appleInterop(
        packageName = "com.artemchep.keyguard.nativecrypto.ffi",
        requireTargetMapping = true,
    )
}

kotlin {
    android {
        namespace = "com.artemchep.keyguard.nativecrypto"

        packaging {
            jniLibs.useLegacyPackaging = false
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(libs.kotlinx.serialization.protobuf)
            }
        }

        sharedJvmMain(name = "jvmCommonMain").dependencies {
            implementation(project(":util:ffi"))
        }

        getByName("iosArm64Main") {
            dependsOn(commonMain)
        }
        getByName("iosSimulatorArm64Main") {
            dependsOn(commonMain)
        }
        getByName("macosArm64Main") {
            dependsOn(commonMain)
        }
    }
}

registerJvmBenchmark(
    name = "nativeCryptoLayerBenchmark",
    description = "Runs the layered Native Crypto JVM overhead benchmark suite from desktopTest.",
    testPattern = "com.artemchep.keyguard.nativecrypto.benchmark.*",
)
