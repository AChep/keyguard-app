import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain
import com.artemchep.keyguard.buildplugins.testing.registerJvmBenchmark
import org.gradle.api.tasks.testing.Test

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("keyguard.rust-multiplatform-library")
    id("keyguard.native-crypto-consumer")
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

// Native C dependencies otherwise inherit the active Xcode SDK's deployment version.
tasks.named<CargoBuildTask>("cargoBuildNativeCryptoMacosArm64") {
    environmentVariables.put(
        "MACOSX_DEPLOYMENT_TARGET",
        libs.versions.appleMacosDeploymentTarget,
    )
}
listOf(
    "cargoBuildNativeCryptoIosArm64",
    "cargoBuildNativeCryptoIosSimulatorArm64",
).forEach { taskName ->
    tasks.named<CargoBuildTask>(taskName) {
        environmentVariables.put(
            "IPHONEOS_DEPLOYMENT_TARGET",
            libs.versions.appleIosDeploymentTarget,
        )
    }
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

tasks.named<Test>("desktopTest") {
    filter {
        excludeTestsMatching("com.artemchep.keyguard.nativecrypto.benchmark.*")
    }
}

registerJvmBenchmark(
    name = "nativeCryptoLayerBenchmark",
    description = "Runs the layered Native Crypto JVM overhead benchmark suite from desktopTest.",
    testPattern = "com.artemchep.keyguard.nativecrypto.benchmark.*",
)
