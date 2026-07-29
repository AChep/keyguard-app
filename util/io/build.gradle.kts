plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    id("keyguard.rust-multiplatform-library")
    id("keyguard.native-io-consumer")
}

kotlin {
    android {
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        namespace = "com.artemchep.keyguard.util.io"

        packaging {
            jniLibs.useLegacyPackaging = false
        }

        withHostTest {}
    }
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.io.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val jvmMain by creating {
            dependsOn(commonMain)
        }

        val androidMain by getting {
            dependsOn(jvmMain)
        }

        val desktopMain by getting {
            dependsOn(jvmMain)
        }

        val appleMain by creating {
            dependsOn(commonMain)
        }

        val iosMain by creating {
            dependsOn(appleMain)
        }

        val iosArm64Main by getting {
            dependsOn(iosMain)
        }

        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
        }

        val macosMain by creating {
            dependsOn(appleMain)
        }

        val macosArm64Main by getting {
            dependsOn(macosMain)
        }

        val androidHostTest by getting {
            dependsOn(commonTest)
        }

        val desktopTest by getting {
            dependsOn(commonTest)
        }

        val iosTest by creating {
            dependsOn(commonTest)
        }

        val iosArm64Test by getting {
            dependsOn(iosTest)
        }

        val iosSimulatorArm64Test by getting {
            dependsOn(iosTest)
        }

        val macosArm64Test by getting {
            dependsOn(commonTest)
        }

        all {
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}
