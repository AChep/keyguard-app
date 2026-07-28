plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
}

kotlin {
    val iosFrameworkName = "KeyguardShared"
    iosArm64 {
        binaries.framework {
            baseName = iosFrameworkName
            binaryOption("bundleId", "com.artemchep.keyguard.shared")
            isStatic = true
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = iosFrameworkName
            binaryOption("bundleId", "com.artemchep.keyguard.shared")
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":common"))
                implementation(libs.jetbrains.compose.runtime)
                implementation(libs.jetbrains.compose.foundation)
                implementation(libs.jetbrains.compose.material3)
            }
        }

        val iosMain by creating {
            dependsOn(commonMain)
        }

        val iosArm64Main by getting {
            dependsOn(iosMain)
        }

        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
        }
    }

    jvmToolchain(libs.versions.jdk.get().toInt())
}
