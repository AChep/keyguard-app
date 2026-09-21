import com.artemchep.keyguard.buildplugins.kotlin.configureComposeIosSwiftRuntime

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
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(project(":common"))
                implementation(libs.jetbrains.compose.runtime)
                implementation(libs.jetbrains.compose.foundation)
                implementation(libs.jetbrains.compose.material3)
            }
        }

        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val iosMain = create("iosMain") {
            dependsOn(commonMain)
        }

        val iosTest = create("iosTest") {
            dependsOn(commonTest)
        }
        getByName("iosArm64Test") {
            dependsOn(iosTest)
        }
        getByName("iosSimulatorArm64Test") {
            dependsOn(iosTest)
        }

        getByName("iosArm64Main") {
            dependsOn(iosMain)
        }

        getByName("iosSimulatorArm64Main") {
            dependsOn(iosMain)
        }
    }

    jvmToolchain(libs.versions.jdk.get().toInt())
}

configureComposeIosSwiftRuntime()
