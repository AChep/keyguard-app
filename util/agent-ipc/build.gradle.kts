plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform")
    id("keyguard.compose-free")
    id("keyguard.rust-desktop-library")
}

kotlin {
    jvm("desktop")
    macosArm64()
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("desktopMain").dependencies {
            implementation(project(":util:ffi"))
        }
    }
}
