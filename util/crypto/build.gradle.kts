import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain
import com.artemchep.keyguard.buildplugins.testing.registerJvmBenchmark
import com.artemchep.keyguard.buildplugins.cargo.RustWasmLibraryExtension
import com.artemchep.keyguard.buildplugins.cargo.StageWasmBrowserProbeTask
import com.artemchep.keyguard.buildplugins.cargo.refreshGeneratedNpmPackage
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBinaryMode
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("keyguard.rust-multiplatform-library")
    id("keyguard.rust-wasm-library")
}

// Enable web targets explicitly; native builds do not require Emscripten or npm dependencies.
val cryptoWasm = providers.gradleProperty("keyguard.crypto.wasm").map(String::toBoolean).getOrElse(false)
val cryptoWasmGc = providers.gradleProperty("keyguard.crypto.wasmGc").map(String::toBoolean).getOrElse(false)
val cryptoWasmNpmDirectory = layout.buildDirectory.dir("wasm/npm")
if (cryptoWasm || cryptoWasmGc) {
    // Kotlin's local npm DSL validates the directory during configuration.
    // Only create the directory here; the dependent Rust task supplies its files.
    cryptoWasmNpmDirectory.get().asFile.mkdirs()
}
extensions.configure<RustWasmLibraryExtension> {
    factoryName.set("createKeyguardCrypto")
    packageFiles.from(layout.projectDirectory.file("wasm/index.mjs"), layout.projectDirectory.file("wasm/package.json"))
    environmentVariables.put("AWS_LC_SYS_CMAKE_BUILDER_wasm32_unknown_emscripten", "0")
    clippyPackages.addAll("keyguard-crypto-core", "keyguard-crypto-sensitive")
    exportedFunctions.set(listOf(
        "malloc", "free", "keyguard_crypto_abi_version", "keyguard_crypto_capabilities", "keyguard_crypto_random_int",
        "keyguard_crypto_call", "keyguard_crypto_stream_open", "keyguard_crypto_stream_update",
        "keyguard_crypto_stream_finish", "keyguard_crypto_stream_close", "keyguard_crypto_buffer_free",
        "keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_encrypt", "keyguard_crypto_aes_cbc_pkcs7_hmac_sha256_decrypt",
    ))
}
val buildNativeCryptoWasm = tasks.named("buildNativeCryptoWasm")

keyguardRust {
    androidCmakeToolchainFile.set(layout.projectDirectory.file("cmake/android.toolchain.cmake"))
    appleInterop(
        packageName = "com.artemchep.keyguard.nativecrypto.ffi",
        requireTargetMapping = true,
    )
}

kotlin {
    if (cryptoWasmGc) {
        wasmJs {
            compilerOptions.freeCompilerArgs.add("-Xwasm-enable-array-range-checks")
            browser {
                testTask {
                    inputs.dir(layout.projectDirectory.dir("wasm/karma.config.d"))
                    inputs.file(layout.projectDirectory.file("wasm/browser-test-setup.mjs"))
                    environment("KEYGUARD_CRYPTO_TEST_SETUP", layout.projectDirectory.file("wasm/browser-test-setup.mjs").asFile.absolutePath)
                    useKarma {
                        useChromeHeadless()
                        useConfigDirectory(layout.projectDirectory.dir("wasm/karma.config.d").asFile)
                    }
                }
            }
            nodejs {
                testTask {
                    nodeJsArgs.add("--import=" + layout.projectDirectory.file("wasm/node-test-setup.mjs").asFile.absolutePath)
                }
            }
            val mainCompilation = compilations.getByName("main")
            val browserProbe = compilations.create("browserProbe") {
                associateWith(mainCompilation)
            }
            val probeBinary = binaries.executable(browserProbe).single { it.mode == KotlinJsBinaryMode.DEVELOPMENT }
            tasks.register<StageWasmBrowserProbeTask>("stageWasmGcBrowserProbe") {
                group = "verification"
                description = "Stages a browser worker that calls the public Kotlin/Wasm crypto API."
                dependsOn(probeBinary.linkSyncTask, buildNativeCryptoWasm)
                entryFile.set(probeBinary.mainFileSyncPath)
                compilerOutput.set(layout.dir(probeBinary.mainFileSyncPath.map { it.asFile.parentFile }))
                runtimePackage.set(cryptoWasmNpmDirectory)
                moduleSpecifier.set("keyguard-crypto-wasm")
                outputDirectory.set(layout.buildDirectory.dir("wasm/browser-probe"))
            }
        }
    }
    if (cryptoWasm) {
        js {
            useEsModules()
            browser {
                testTask {
                    inputs.dir(layout.projectDirectory.dir("wasm/karma.config.d"))
                    inputs.file(layout.projectDirectory.file("wasm/browser-test-setup.mjs"))
                    environment("KEYGUARD_CRYPTO_TEST_SETUP", layout.projectDirectory.file("wasm/browser-test-setup.mjs").asFile.absolutePath)
                    useKarma {
                        useChromeHeadless()
                        useConfigDirectory(layout.projectDirectory.dir("wasm/karma.config.d").asFile)
                    }
                }
            }
            nodejs {
                testTask {
                    useMocha { timeout = "180s" }
                    nodeJsArgs.add("--import=" + layout.projectDirectory.file("wasm/node-test-setup.mjs").asFile.absolutePath)
                }
            }
        }
    }
    android {
        namespace = "com.artemchep.keyguard.nativecrypto"

        packaging {
            jniLibs.useLegacyPackaging = false
        }
    }

    sourceSets {
        if (cryptoWasmGc) {
            getByName("wasmJsMain").dependencies {
                implementation(npm("keyguard-crypto-wasm", cryptoWasmNpmDirectory.get().asFile))
            }
            getByName("wasmJsTest") {
                kotlin.srcDir("src/jsTest/kotlin")
                dependencies { implementation(libs.kotlinx.coroutines.test) }
            }
        }
        if (cryptoWasm) {
            getByName("jsMain").dependencies {
                implementation(npm("keyguard-crypto-wasm", cryptoWasmNpmDirectory.get().asFile))
            }
            getByName("jsTest").dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(libs.kotlinx.serialization.protobuf)
            }
        }

        sharedJvmMain(name = "jvmCommonMain").dependencies {
            implementation(project(":util:ffi"))
        }

        getByName("iosArm64Main") {
            dependsOn(commonMain)
        }
        getByName("iosSimulatorArm64Main") {
            dependsOn(commonMain)
        }
        getByName("macosArm64Main") {
            dependsOn(commonMain)
        }
    }

    // These parity tests exercise thread-backed Rust OpenPGP sessions. Keep
    // them on every native test compilation; web has explicit contract tests.
    targets.matching { it.name != "js" && it.name != "wasmJs" }.configureEach {
        compilations.matching { it.name.endsWith("test", ignoreCase = true) }.configureEach {
            defaultSourceSet.kotlin.srcDir("src/threadedTest/kotlin")
        }
    }
}

if (cryptoWasmGc) {
    rootProject.extensions.configure<WasmNodeJsEnvSpec> { download.set(false) }
    extensions.configure<WasmNodeJsEnvSpec> { download.set(false) }
    rootProject.extensions.configure<WasmYarnRootEnvSpec> { download.set(false) }
    rootProject.extensions.configure<WasmYarnRootExtension> {
        lockFileDirectoryProperty.set(layout.projectDirectory.dir("wasm"))
        lockFileNameProperty.set("wasm-yarn.lock")
    }
    tasks.matching { it.name.startsWith("wasmJs") && it.name.endsWith("PackageJson") }.configureEach {
        dependsOn(buildNativeCryptoWasm)
    }
    refreshGeneratedNpmPackage(
        installTaskName = "kotlinWasmNpmInstall",
        packageName = "keyguard-crypto-wasm",
        producer = buildNativeCryptoWasm,
        packageDirectory = cryptoWasmNpmDirectory,
        nodeModulesDirectory = rootProject.layout.buildDirectory.dir("wasm/node_modules"),
    )
}

if (cryptoWasm) {
    // Use the activated SDK's Node and the installed Yarn. Tool downloads add
    // project repositories, which this repository intentionally prohibits.
    rootProject.extensions.configure<NodeJsEnvSpec> {
        download.set(false)
    }
    extensions.configure<NodeJsEnvSpec> {
        download.set(false)
    }
    rootProject.extensions.configure<YarnRootEnvSpec> {
        download.set(false)
    }
    rootProject.extensions.configure<YarnRootExtension> {
        lockFileDirectoryProperty.set(layout.projectDirectory.dir("wasm"))
    }
    tasks.matching { it.name == "jsPackageJson" || it.name == "jsTestPackageJson" }.configureEach {
        dependsOn(buildNativeCryptoWasm)
    }
    refreshGeneratedNpmPackage(
        installTaskName = "kotlinNpmInstall",
        packageName = "keyguard-crypto-wasm",
        producer = buildNativeCryptoWasm,
        packageDirectory = cryptoWasmNpmDirectory,
        nodeModulesDirectory = rootProject.layout.buildDirectory.dir("js/node_modules"),
    )
}

registerJvmBenchmark(
    name = "nativeCryptoLayerBenchmark",
    description = "Runs the layered Native Crypto JVM overhead benchmark suite from desktopTest.",
    testPattern = "com.artemchep.keyguard.nativecrypto.benchmark.*",
)
