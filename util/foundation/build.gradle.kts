plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    id("keyguard.native-crypto-consumer")
}

kotlin {
    android {
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        namespace = "com.artemchep.keyguard.util.foundation"

        withHostTest {}
    }
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":util:crypto"))
                api(libs.kotlinx.io.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmCommonTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(libs.bouncycastle.bcprov)
            }
        }
        val androidHostTest by getting {
            dependsOn(jvmCommonTest)
        }
        val desktopTest by getting {
            dependsOn(jvmCommonTest)
        }

        val jvmCommonMain by creating {
            dependsOn(commonMain)
        }
        val androidMain by getting {
            dependsOn(jvmCommonMain)
        }
        val desktopMain by getting {
            dependsOn(jvmCommonMain)
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

        all {
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}
