import com.artemchep.keyguard.buildplugins.cargo.configureNativeLibraryTests
import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
    id("keyguard.rust-desktop-library")
}

kotlin {
    android { namespace = "com.artemchep.keyguard.util.fido2" }
    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.coroutines.core) }
        sharedJvmMain()
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

configureNativeLibraryTests("fido2", testTaskName = "desktopTest")
tasks.named<Test>("desktopTest") {
    jvmArgs("-Xcheck:jni")
}
