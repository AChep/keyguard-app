plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform")
}

// The library itself doesn't bundle the actual native binaries
// and relies on the @desktopApp module to do so!

kotlin {
    explicitApi()

    jvm()

    sourceSets {
        getByName("jvmMain") {
            dependencies {
                implementation(libs.java.jna)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.dbus.java.core)
                implementation(libs.dbus.java.transport)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
