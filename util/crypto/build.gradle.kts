import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.artemchep.keyguard.buildplugins.androidssh.AndroidCargoEnvironment
import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask
import com.artemchep.keyguard.buildplugins.cargo.PrepareNativeLibraryTask
import com.artemchep.keyguard.buildplugins.cargo.VerifyElfPageAlignmentTask
import com.artemchep.keyguard.buildplugins.cargo.VerifyRustTargetInstalledTask
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform
import com.artemchep.keyguard.buildplugins.cargo.dynamicLibraryName
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.plugin.serialization)
    id("keyguard.cargo-common")
    id("keyguard.native-crypto-consumer")
}

data class AndroidNativeTarget(
    val rustTarget: String,
    val androidAbi: String,
)

data class AppleNativeTarget(
    val kotlinTarget: String,
    val rustTarget: String,
)

fun String.toTaskSuffix(): String = split('-', '_')
    .filter(String::isNotBlank)
    .joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }

val rustSourceDirectory = layout.projectDirectory.dir("rust")
val schemaDirectory = layout.projectDirectory.dir("schema")
val reviewedRustForksDirectory = rootProject.layout.projectDirectory.dir("thirdParty/rust")
val androidCmakeToolchainFile = layout.projectDirectory.file("cmake/android.toolchain.cmake")

val androidNativeTargets = listOf(
    AndroidNativeTarget("aarch64-linux-android", "arm64-v8a"),
    AndroidNativeTarget("armv7-linux-androideabi", "armeabi-v7a"),
    AndroidNativeTarget("i686-linux-android", "x86"),
    AndroidNativeTarget("x86_64-linux-android", "x86_64"),
)
val appleNativeTargets = listOf(
    AppleNativeTarget("iosArm64", "aarch64-apple-ios"),
    AppleNativeTarget("iosSimulatorArm64", "aarch64-apple-ios-sim"),
    AppleNativeTarget("macosArm64", "aarch64-apple-darwin"),
)

val hostPlatform = detectHostPlatform()
val desktopLibraryFileName = hostPlatform.dynamicLibraryName("keyguard_crypto_jni")
val cargoOffline = providers.gradleProperty("keyguard.nativeCrypto.cargoOffline")
    .map(String::toBooleanStrict)
    .orElse(false)

tasks.withType<CargoBuildTask>().configureEach {
    offline.set(cargoOffline)
}

keyguardCargo {
    sourceDir.set(rustSourceDirectory)
    extraSourceInputs.from(schemaDirectory, reviewedRustForksDirectory)
    rustTarget.set(hostPlatform.desktopLibRustTarget)
    cargoPackage.set("keyguard-crypto-jni")
    cargoArguments.add("--locked")
    cargoBinaryName.set(desktopLibraryFileName)
    packagedBinaryName.set(desktopLibraryFileName)
    composeResourceDir.set(hostPlatform.composeResourceDir)
    cargoTaskName.set("cargoBuildNativeCryptoDesktop")
    compileTaskName.set("compileNativeCryptoDesktop")
    platformMacOs.set(hostPlatform.isMacOs)
    platformWindows.set(hostPlatform.isWindows)
    markExecutable.set(true)
}

val verifyDesktopRustTarget = tasks.register<VerifyRustTargetInstalledTask>(
    "verifyNativeCryptoDesktopRustTarget",
) {
    sourceDir.set(rustSourceDirectory)
    rustTarget.set(hostPlatform.desktopLibRustTarget)
}
tasks.matching { task -> task.name == "cargoBuildNativeCryptoDesktop" }.configureEach {
    dependsOn(verifyDesktopRustTarget)
}

val androidPrepareTasks = androidNativeTargets.map { target ->
    val suffix = target.androidAbi.toTaskSuffix()
    val cargoTargetDirectory = layout.buildDirectory
        .dir("native-crypto-cargo-target/android/${target.rustTarget}")
    val cargoOutputBinary = cargoTargetDirectory.map { directory ->
        directory.file("${target.rustTarget}/release/libkeyguard_crypto_jni.so")
    }
    val verifyRustTarget = tasks.register<VerifyRustTargetInstalledTask>(
        "verifyNativeCryptoAndroid${suffix}RustTarget",
    ) {
        sourceDir.set(rustSourceDirectory)
        rustTarget.set(target.rustTarget)
    }
    val androidBuildTools = providers.provider {
        AndroidCargoEnvironment.resolveTargetBuildTools(
            rootDir = rootProject.projectDir,
            localPropertiesFile = null,
            rustTarget = target.rustTarget,
            androidApiLevel = libs.versions.androidMinSdk.get().toInt(),
            ndkVersion = libs.versions.androidNdk.get(),
        )
    }
    val ndkDirectoryPath = androidBuildTools.map { tools ->
        tools.toolchain.ndkDir.absolutePath
    }
    val linkerExecutable = layout.file(
        androidBuildTools.map { tools -> tools.cCompiler },
    )
    val cargoBuild = tasks.register<CargoBuildTask>("cargoBuildNativeCryptoAndroid$suffix") {
        dependsOn(verifyRustTarget)
        sourceDir.set(rustSourceDirectory)
        sourceFiles.from(
            fileTree(rustSourceDirectory) {
                exclude("target/**", "**/target/**")
            },
            fileTree(schemaDirectory),
            fileTree(reviewedRustForksDirectory),
            androidCmakeToolchainFile,
        )
        this.cargoTargetDir.set(cargoTargetDirectory)
        rustTarget.set(target.rustTarget)
        cargoPackage.set("keyguard-crypto-jni")
        cargoArguments.add("--locked")
        environmentVariables.put(
            AndroidCargoEnvironment.rustFlagsEnvironmentName(target.rustTarget),
            "-C link-arg=-Wl,-z,max-page-size=16384 " +
                "-C link-arg=-Wl,-z,common-page-size=16384",
        )
        environmentVariables.put("ANDROID_NDK_ROOT", ndkDirectoryPath)
        environmentVariables.put("ANDROID_NDK", ndkDirectoryPath)
        environmentVariables.put(
            AndroidCargoEnvironment.targetEnvironmentName("CC", target.rustTarget),
            androidBuildTools.map { tools -> tools.cCompiler.absolutePath },
        )
        environmentVariables.put(
            AndroidCargoEnvironment.targetEnvironmentName("CXX", target.rustTarget),
            androidBuildTools.map { tools -> tools.cxxCompiler.absolutePath },
        )
        environmentVariables.put(
            AndroidCargoEnvironment.targetEnvironmentName("AR", target.rustTarget),
            androidBuildTools.map { tools -> tools.archiver.absolutePath },
        )
        environmentVariables.put(
            AndroidCargoEnvironment.targetEnvironmentName("RANLIB", target.rustTarget),
            androidBuildTools.map { tools -> tools.ranlib.absolutePath },
        )
        environmentVariables.put(
            AndroidCargoEnvironment.targetEnvironmentName("CMAKE_TOOLCHAIN_FILE", target.rustTarget),
            androidCmakeToolchainFile.asFile.absolutePath,
        )
        environmentVariables.put("KEYGUARD_ANDROID_ABI", target.androidAbi)
        environmentVariables.put(
            "KEYGUARD_ANDROID_API_LEVEL",
            libs.versions.androidMinSdk.get(),
        )
        outputBinary.set(cargoOutputBinary)
        configureAndroidLinker(linkerExecutable)
    }
    val verifyAlignment = tasks.register<VerifyElfPageAlignmentTask>(
        "verifyNativeCryptoAndroid${suffix}PageAlignment",
    ) {
        dependsOn(cargoBuild)
        readElfExecutable.set(
            layout.file(
                providers.provider {
                    AndroidCargoEnvironment.resolveReadElfExecutable(
                        rootDir = rootProject.projectDir,
                        localPropertiesFile = null,
                        ndkVersion = libs.versions.androidNdk.get(),
                    )
                },
            ),
        )
        binary.set(cargoBuild.flatMap { task -> task.outputBinary })
    }
    tasks.register<PrepareNativeLibraryTask>("prepareNativeCryptoAndroid$suffix") {
        dependsOn(verifyAlignment)
        sourceBinary.set(cargoBuild.flatMap { task -> task.outputBinary })
        relativeDirectory.set(target.androidAbi)
        packagedFileName.set("libkeyguard_crypto_jni.so")
        outputDirectory.set(
            layout.buildDirectory.dir("generated/nativeCrypto/jniLibs/${target.androidAbi}"),
        )
    }
}

extensions.configure<KotlinMultiplatformAndroidComponentsExtension>("androidComponents") {
    onVariants(selector().all()) { variant ->
        val jniLibs = requireNotNull(variant.sources.jniLibs) {
            "The nativeCrypto Android variant must expose a jniLibs source set"
        }
        androidPrepareTasks.forEach { prepareTask ->
            jniLibs.addGeneratedSourceDirectory(prepareTask) { task -> task.outputDirectory }
        }
    }
}

val appleCargoTasks = appleNativeTargets.associate { target ->
    val suffix = target.kotlinTarget.replaceFirstChar(Char::uppercaseChar)
    val cargoTargetDirectory = layout.buildDirectory
        .dir("native-crypto-cargo-target/apple/${target.rustTarget}")
    val cargoOutputBinary = cargoTargetDirectory.map { directory ->
        directory.file("${target.rustTarget}/release/libkeyguard_crypto_c.a")
    }
    val verifyRustTarget = tasks.register<VerifyRustTargetInstalledTask>(
        "verifyNativeCrypto${suffix}RustTarget",
    ) {
        sourceDir.set(rustSourceDirectory)
        rustTarget.set(target.rustTarget)
    }
    val cargoBuild = tasks.register<CargoBuildTask>("cargoBuildNativeCrypto$suffix") {
        dependsOn(verifyRustTarget)
        sourceDir.set(rustSourceDirectory)
        sourceFiles.from(
            fileTree(rustSourceDirectory) {
                exclude("target/**", "**/target/**")
            },
            fileTree(schemaDirectory),
            fileTree(reviewedRustForksDirectory),
        )
        this.cargoTargetDir.set(cargoTargetDirectory)
        rustTarget.set(target.rustTarget)
        cargoPackage.set("keyguard-crypto-c")
        cargoArguments.add("--locked")
        outputBinary.set(cargoOutputBinary)
    }
    target.kotlinTarget to cargoBuild
}

kotlin {
    android {
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        namespace = "com.artemchep.keyguard.nativecrypto"

        packaging {
            jniLibs.useLegacyPackaging = false
        }

        withHostTest {}
    }
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        val targetSpec = checkNotNull(
            appleNativeTargets.firstOrNull { target -> target.kotlinTarget == name },
        ) {
            "Missing nativeCrypto Rust target mapping for Kotlin target '$name'"
        }
        val cargoBuild = checkNotNull(appleCargoTasks[name])
        val staticLibraryDirectory = layout.buildDirectory
            .dir(
                "native-crypto-cargo-target/apple/${targetSpec.rustTarget}/" +
                    "${targetSpec.rustTarget}/release",
            )
            .get()
            .asFile
            .absolutePath

        compilations.getByName("main").cinterops.create("nativeCrypto") {
            definitionFile.set(
                layout.projectDirectory.file("src/nativeInterop/cinterop/nativeCrypto.def"),
            )
            packageName("com.artemchep.keyguard.nativecrypto.ffi")
            includeDirs(
                layout.projectDirectory.dir("rust/crates/keyguard-crypto-c/include"),
            )
            extraOpts("-libraryPath", staticLibraryDirectory)
        }

        val interopTaskName = "cinteropNativeCrypto${name.replaceFirstChar(Char::uppercaseChar)}"
        tasks.matching { task -> task.name == interopTaskName }.configureEach {
            dependsOn(cargoBuild)
            inputs.file(cargoBuild.flatMap { task -> task.outputBinary })
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.protobuf)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmCommonMain by creating {
            dependsOn(commonMain)
        }
        val androidMain by getting {
            dependsOn(jvmCommonMain)
        }
        val desktopMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.java.jna)
            }
        }

        val iosArm64Main by getting {
            dependsOn(commonMain)
            kotlin.srcDir("src/appleInteropMain/kotlin")
        }
        val iosSimulatorArm64Main by getting {
            dependsOn(commonMain)
            kotlin.srcDir("src/appleInteropMain/kotlin")
        }
        val macosArm64Main by getting {
            dependsOn(commonMain)
            kotlin.srcDir("src/appleInteropMain/kotlin")
        }
    }

    jvmToolchain(libs.versions.jdk.get().toInt())
}

val compileNativeCryptoAndroidAll = tasks.register("compileNativeCryptoAndroidAll") {
    group = "build"
    description = "Builds and verifies nativeCrypto JNI libraries for every supported Android ABI."
    dependsOn(androidPrepareTasks)
}
val compileNativeCryptoAppleAll = tasks.register("compileNativeCryptoAppleAll") {
    group = "build"
    description = "Builds nativeCrypto static libraries for all supported Apple targets."
    dependsOn(appleCargoTasks.values)
}
appleCargoTasks.forEach { (targetName, cargoTask) ->
    tasks.register("compileNativeCrypto${targetName.replaceFirstChar(Char::uppercaseChar)}") {
        group = "build"
        description = "Builds the nativeCrypto static library for $targetName."
        dependsOn(cargoTask)
    }
}
tasks.register("compileNativeCryptoDesktop${hostPlatform.name}") {
    group = "build"
    description = "Builds the nativeCrypto JNI library for the current ${hostPlatform.name} host."
    dependsOn("compileNativeCryptoDesktop")
}
tasks.register("compileNativeCryptoAll") {
    group = "build"
    description = "Builds nativeCrypto artifacts for Android, the current Desktop host, and Apple."
    dependsOn(compileNativeCryptoAndroidAll)
    dependsOn(compileNativeCryptoAppleAll)
    dependsOn("compileNativeCryptoDesktop")
}

tasks.named("assemble") {
    dependsOn("compileNativeCryptoDesktop")
}

val desktopTestTask = tasks.named<Test>("desktopTest")
val desktopTestClassesTask = tasks.named("desktopTestClasses")

desktopTestTask.configure {
    filter {
        excludeTestsMatching("com.artemchep.keyguard.nativecrypto.benchmark.*")
    }
}

tasks.register<Test>("nativeCryptoLayerBenchmark") {
    group = "verification"
    description = "Runs the layered Native Crypto JVM overhead benchmark suite from desktopTest."

    dependsOn(desktopTestClassesTask)

    testClassesDirs = desktopTestTask.get().testClassesDirs
    classpath = desktopTestTask.get().classpath

    maxParallelForks = 1
    forkEvery = 0L
    outputs.upToDateWhen { false }

    systemProperty("user.language", "en")
    systemProperty("user.country", "US")

    filter {
        includeTestsMatching("com.artemchep.keyguard.nativecrypto.benchmark.*")
        isFailOnNoMatchingTests = true
    }

    testLogging {
        events =
            setOf(
                TestLogEvent.FAILED,
                TestLogEvent.PASSED,
                TestLogEvent.SKIPPED,
                TestLogEvent.STANDARD_ERROR,
                TestLogEvent.STANDARD_OUT,
            )
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showStackTraces = true
        showStandardStreams = true
    }
}
