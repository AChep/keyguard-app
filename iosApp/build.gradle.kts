plugins {
    id("keyguard.quality")
    id("keyguard.koin")
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
}

// Application roots always revalidate the assembled dependency graph. On Kotlin/Native the
// compiler plugin cannot read definitions from other modules' klibs and reports false
// KOIN-D002 errors for every common binding (koin-compiler-plugin issues #105, #106), so
// this root relies on IosKoinGraphTest until upstream fixes cross-module hints for Native.
koinCompiler {
    strictSafety.set(true)
    compileSafety.set(false)
}

kotlin {
    val iosFrameworkName = "KeyguardShared"
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = iosFrameworkName
            binaryOption("bundleId", "com.artemchep.keyguard.shared")
            isStatic = true
        }
        target.binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
            // Xcode supplies SQLCipher to the app. Standalone graph tests need the SQLite
            // API symbols, but deliberately never open an encrypted vault database.
            linkerOpts("-lsqlite3")
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(project(":common"))
                implementation(libs.jetbrains.compose.runtime)
                implementation(libs.jetbrains.compose.foundation)
                implementation(libs.jetbrains.compose.material3)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val iosMain by creating {
            dependsOn(commonMain)
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

        val iosArm64Main by getting {
            dependsOn(iosMain)
        }

        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
        }
    }

    jvmToolchain(libs.versions.jdk.get().toInt())
}
