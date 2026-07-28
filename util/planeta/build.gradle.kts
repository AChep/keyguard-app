plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
}

kotlin {
    android {
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        namespace = "com.artemchep.keyguard.util.planeta"

        withHostTest {}
    }
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.jetbrains.compose.runtime)
                implementation(libs.jetbrains.compose.foundation)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}
