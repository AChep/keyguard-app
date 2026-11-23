plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform")
    id("keyguard.compose-free")
    id("keyguard.rust-desktop-library")
}

kotlin {
    jvm("desktop")
    macosArm64()
    sourceSets.getByName("desktopMain").dependencies {
        implementation(project(":util:ffi"))
    }
    sourceSets.getByName("macosArm64Main").dependencies {
        implementation(project(":util:ffi"))
    }
}
