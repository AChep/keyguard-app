plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    id("keyguard.rust-multiplatform-library")
    id("keyguard.native-zxcvbn-consumer")
}

kotlin {
    android {
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        namespace = "com.artemchep.keyguard.util.zxcvbn"

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
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                // `runTest` is the only multiplatform way to await coroutines
                // from common test code: `runBlocking` is not part of the
                // common surface of kotlinx-coroutines-core.
                implementation(libs.kotlinx.coroutines.test)
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
