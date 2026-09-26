import com.artemchep.keyguard.buildplugins.kotlin.configureComposeIosSwiftRuntime
import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleTest
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("keyguard.quality")
    id("keyguard.license-policy")
    id("keyguard.koin")
    id("keyguard.kotlin-multiplatform")
    // Resolves the reified `encodeToString` / `serializer<T>()` calls at compile time
    // instead of through the runtime serializer lookup.
    alias(libs.plugins.kotlin.plugin.serialization)
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
    val xcframework = XCFramework("KeyguardShared")
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "KeyguardShared"
            binaryOption("bundleId", "com.artemchep.keyguard.shared")
            isStatic = true
            xcframework.add(this)
        }
        // DI smoke tests resolve SQLite bindings without opening a vault. App
        // frameworks continue to obtain SQLCipher from the Xcode package.
        target.binaries.withType<TestExecutable>().configureEach {
            linkerOpts("-lsqlite3")
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.koin.core)
                implementation(project(":common"))
                implementation(libs.jetbrains.compose.runtime)
                implementation(libs.jetbrains.compose.material3)
                implementation(libs.jetbrains.compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        sharedAppleMain()

        // Runtime tests for the Apple bridge; `iosTest` holds the graph tests
        // for the iOS application root.
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        sharedAppleTest()
    }
}

configureComposeIosSwiftRuntime()

val keyguardSharedSwiftPackageDir =
    layout.buildDirectory.dir("XCFrameworks/swiftpm/KeyguardShared.xcframework")

// xcode/scripts/build-shared-framework.sh picks the Debug or Release task from
// Xcode's $CONFIGURATION, so each sync publishes its own configuration.
listOf("Debug", "Release").forEach { config ->
    val assembleTaskName = "assembleKeyguardShared${config}XCFramework"
    val syncSourceXcframework =
        layout.buildDirectory.dir("XCFrameworks/${config.lowercase()}/KeyguardShared.xcframework")
    val syncSwiftPackageXCFramework = tasks.register<Exec>(
        "syncKeyguardShared${config}XCFrameworkForSwiftPackage",
    ) {
        group = "build"
        description = "Copies the $config KeyguardShared XCFramework to the stable SwiftPM binary target path."
        dependsOn(assembleTaskName)
        // Up-to-date while the framework is unchanged. Gradle's Sync would not
        // preserve the framework symlinks, hence ditto.
        inputs.dir(syncSourceXcframework)
        outputs.dir(keyguardSharedSwiftPackageDir)
        commandLine(
            "/usr/bin/ditto",
            syncSourceXcframework.get().asFile.absolutePath,
            keyguardSharedSwiftPackageDir.get().asFile.absolutePath,
        )
        doFirst {
            val dest = keyguardSharedSwiftPackageDir.get().asFile
            dest.deleteRecursively()
            dest.parentFile.mkdirs()
        }
    }

    tasks.named(assembleTaskName) {
        dependsOn(
            ":common:iosArm64AggregateResources",
            ":common:iosSimulatorArm64AggregateResources",
            ":common:macosArm64AggregateResources",
        )
        finalizedBy(syncSwiftPackageXCFramework)
    }
}
