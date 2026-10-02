import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask
import com.artemchep.keyguard.buildplugins.cargo.HostPlatform
import com.artemchep.keyguard.buildplugins.cargo.configureNativeLibraryTests
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform
import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
    id("keyguard.rust-desktop-library")
}

// hidapi's C sources must not inherit the active Xcode SDK's deployment version.
tasks.named<CargoBuildTask>("cargoBuildNativeYubikeyMacosArm64") {
    environmentVariables.put("MACOSX_DEPLOYMENT_TARGET", libs.versions.appleMacosDeploymentTarget)
}
val desktopHost = detectHostPlatform()
if (desktopHost.isMacOs) {
    tasks.named<CargoBuildTask>("cargoBuildNativeYubikeyDesktop") {
        // Match Rust's desktop target baseline independently of the native Apple app.
        val minimumVersion = if (desktopHost == HostPlatform.MacosArm64) "11.0" else "10.12"
        environmentVariables.put("MACOSX_DEPLOYMENT_TARGET", minimumVersion)
    }
}

kotlin {
    android { namespace = "com.artemchep.keyguard.util.yubikey" }
    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.coroutines.core) }
        getByName("androidMain").dependencies {
            implementation(libs.yubico.yubikit.android)
            implementation(libs.yubico.yubikit.yubiotp)
        }
        sharedAppleMain()
        val nativeClientMain by creating {
            dependsOn(commonMain.get())
            dependencies { implementation(project(":util:ffi")) }
        }
        getByName("desktopMain").dependsOn(nativeClientMain)
        getByName("appleMain").dependsOn(nativeClientMain)
        val nativeClientTest by creating { dependsOn(commonTest.get()) }
        getByName("desktopTest").dependsOn(nativeClientTest)
        getByName("macosArm64Test").dependsOn(nativeClientTest)
    }
}

configureNativeLibraryTests("yubikey", testTaskName = "desktopTest")
tasks.named<Test>("desktopTest") {
    jvmArgs("-Xcheck:jni")
}
