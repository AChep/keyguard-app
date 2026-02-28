plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The library itself doesn't bundle the actual native binaries
// and relies on the @desktopApp module to do so!

kotlin {
    explicitApi()

    jvm {
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(libs.java.jna)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.dbus.java.core)
                implementation(libs.dbus.java.transport)
            }
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}
