import com.artemchep.keyguard.buildplugins.kotlin.configureComposeIosSwiftRuntime
import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleTest
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFrameworkTask

plugins {
    id("keyguard.application-root")
    id("keyguard.kotlin-multiplatform")
    // Resolves the reified `encodeToString` / `serializer<T>()` calls at compile time
    // instead of through the runtime serializer lookup.
    alias(libs.plugins.kotlin.plugin.serialization)
    // Shared row constructors used by bridge tests contain @Composable lambdas.
    // Match their lowered signatures even though this module renders no Compose UI.
    alias(libs.plugins.kotlin.plugin.compose)
}

// On Kotlin/Native the compiler plugin cannot read definitions from other modules' klibs and
// reports false KOIN-D002 errors for every common binding (koin-compiler-plugin issues #105,
// #106), so this root relies on IosKoinGraphTest until upstream fixes cross-module hints for Native.
koinCompiler {
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
        commonMain {
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
        commonTest {
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

/** Copies [xcframework] to the stable SwiftPM binary target path after [assembleTaskName]. */
fun publishToSwiftPackage(
    assembleTaskName: String,
    xcframework: Provider<Directory>,
) {
    val source = xcframework.get().asFile
    val destination = keyguardSharedSwiftPackageDir.get().asFile
    val syncTask = tasks.register<Exec>(
        assembleTaskName.replaceFirst("assemble", "sync") + "ForSwiftPackage",
    ) {
        group = "build"
        description = "Copies the result of $assembleTaskName to the stable SwiftPM binary target path."
        dependsOn(assembleTaskName)
        // Up-to-date while the framework is unchanged. Gradle's Sync would not
        // preserve the framework symlinks, hence ditto.
        inputs.dir(source)
        outputs.dir(destination)
        commandLine("/usr/bin/ditto", source.absolutePath, destination.absolutePath)
        doFirst {
            destination.deleteRecursively()
            destination.parentFile.mkdirs()
        }
    }
    tasks.named(assembleTaskName) {
        finalizedBy(syncTask)
    }
}

// Every slice: CI's link check and the tests that run outside an app build.
listOf("Debug", "Release").forEach { config ->
    val assembleTaskName = "assembleKeyguardShared${config}XCFramework"
    tasks.named(assembleTaskName) {
        dependsOn(
            ":common:iosArm64AggregateResources",
            ":common:iosSimulatorArm64AggregateResources",
            ":common:macosArm64AggregateResources",
        )
    }
    publishToSwiftPackage(
        assembleTaskName = assembleTaskName,
        xcframework = layout.buildDirectory.dir("XCFrameworks/${config.lowercase()}/KeyguardShared.xcframework"),
    )
}

// One slice: Xcode builds a single SDK, so xcode/scripts/build-shared-framework.sh
// picks the task from $SDK_NAME and $CONFIGURATION.
kotlin.targets.withType<KotlinNativeTarget>().configureEach {
    val target = this
    val slice = target.name.replaceFirstChar(Char::uppercaseChar)
    binaries.withType<Framework>().configureEach {
        val framework = this
        val config = framework.buildType.getName().replaceFirstChar(Char::uppercaseChar)
        val outputDir = layout.buildDirectory.dir("XCFrameworks/${target.name}")
        val assembleTaskName = "assembleKeyguardShared$slice${config}XCFramework"
        tasks.register<XCFrameworkTask>(assembleTaskName) {
            group = "build"
            description = "Assembles the $config KeyguardShared XCFramework with only the ${target.name} slice."
            baseName = provider { framework.baseName }
            buildType = framework.buildType
            from(framework)
            this.outputDir = outputDir.get().asFile
            // xcode/scripts/copy-compose-resources.sh copies this slice's resources.
            dependsOn(":common:${target.name}AggregateResources")
        }
        publishToSwiftPackage(
            assembleTaskName = assembleTaskName,
            xcframework = outputDir.map { it.dir("${config.lowercase()}/KeyguardShared.xcframework") },
        )
    }
}
