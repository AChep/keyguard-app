import com.artemchep.keyguard.buildplugins.testing.benchmarkReport
import com.artemchep.keyguard.buildplugins.testing.flightRecorder
import com.artemchep.keyguard.buildplugins.testing.forwardSystemProperties
import com.artemchep.keyguard.buildplugins.testing.registerJvmBenchmark
import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.INT
import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING
import com.artemchep.keyguard.buildplugins.version.createVersionInfo
import org.gradle.api.tasks.testing.Test
import java.time.Duration

plugins {
    id("keyguard.quality")
    id("keyguard.koin")
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.plugin.parcelize)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.buildkonfig)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    id("keyguard.resources-common")
    id("keyguard.native-crypto-consumer")
    id("keyguard.native-io-consumer")
    id("keyguard.native-zxcvbn-consumer")
    id("keyguard.detekt-custom-rules")
    id("keyguard.crypto-dependency-check")
}

// `android`/`main` covers commonMain plus androidMain; `desktop`/`main` covers commonMain plus
// desktopMain. Together they make every JVM-reachable source set checkable.
//
// Detekt registers type-resolution tasks only for JVM and Android targets, so a call site in
// appleMain or iosMain can never be analysed. verifyDetektCustomRulesCoverage fails on one
// instead, which blocks it rather than letting it go unchecked.
detektCustomRules {
    kmpCompilation(targetName = "android", compilationName = "main")
    kmpCompilation(targetName = "desktop", compilationName = "main")
    // Host-only tests do not become part of an Android artifact.
    excludeSourcePathFromCoverage("src/commonTest")
    excludeSourcePathFromCoverage("src/desktopTest")
}

//
// Obtain the build configuration
//

val versionInfo = createVersionInfo(
    marketingVersion = libs.versions.appVersionName.get(),
    logicalVersion = libs.versions.appVersionCode.get().toInt(),
)

tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(10))
}

kotlin {
    android {
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        namespace = "com.artemchep.keyguard.common"

        compilerOptions {
            enableCoreLibraryDesugaring = true
        }

        androidResources.enable = true

        withHostTest {
            isIncludeAndroidResources = true
        }
    }
    jvm("desktop")
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    sourceSets {
        all {
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("androidx.compose.animation.ExperimentalAnimationApi")
            languageSettings.optIn("androidx.compose.material.ExperimentalMaterialApi")
            languageSettings.optIn("androidx.compose.foundation.ExperimentalFoundationApi")
            languageSettings.optIn("androidx.compose.foundation.layout.ExperimentalLayoutApi")
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(project(":standard:presentation"))
                implementation(libs.jetbrains.compose.runtime)
                implementation(libs.jetbrains.compose.foundation)
                implementation(libs.jetbrains.compose.material)
                implementation(libs.jetbrains.compose.material3)
                api(libs.jetbrains.compose.material.icons.extended)
                implementation(libs.jetbrains.compose.ui.tooling.preview)
                api(libs.jetbrains.compose.components.resources)
                api(libs.kotlin.stdlib)
                implementation(libs.kotlinx.atomicfu)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.collections.immutable)
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.io.core)
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.serialization.cbor)
                api(libs.kotlinx.serialization.protobuf)
                api(libs.arrow.arrow.cache4k)
                api(libs.arrow.arrow.core)
                api(libs.arrow.arrow.functions)
                api(libs.arrow.arrow.optics)
                api(libs.koin.core)
                implementation(libs.koin.compose)
                api(libs.androidx.lifecycle.common)
                api(libs.androidx.lifecycle.runtime)
                api(libs.androidx.lifecycle.runtime.compose)
                api(libs.ktor.ktor.client.core)
                api(libs.ktor.ktor.client.logging)
                api(libs.ktor.ktor.client.content.negotiation)
                api(libs.ktor.ktor.client.websockets)
                api(libs.ktor.ktor.serialization.kotlinx)
                api(project(":util:foundation"))
                api(project(":util:io"))
                api(project(":util:zxcvbn"))
                api(project(":util:zip"))
                api(project(":util:kdbx"))
                api(project(":util:crypto"))
                api(project(":util:signalr"))
                api(project(":util:webdav"))
                api(project(":util:planeta"))
                api(libs.coil3.coil.compose)
                api(libs.coil3.coil.network.ktor3)
                api(libs.cash.sqldelight.coroutines.extensions)
                api(libs.devsrsouza.feather)
                api(libs.haze.core)
                api(libs.haze.blur)
                api(libs.ksoup.html)
                api(libs.snipme.highlights)
                api(libs.kdroidfilter.platformtools.darkmodedetector)
            }
        }
        // html-text-material3 does not publish macOS klibs; the HtmlText
        // composable is provided via expect/actual instead (macOS gets a
        // plain-text fallback in macosMain).
        val commonTest = getByName("commonTest") {
            kotlin.setSrcDirs(emptyList<String>())
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val jvmTest = create("jvmTest") {
            dependsOn(commonTest)
            kotlin.srcDir("src/commonTest/kotlin")
            dependencies {
                implementation(libs.bouncycastle.bcpkix)
                implementation(libs.bouncycastle.bcpg)
                implementation(libs.bouncycastle.bcprov)
                implementation(libs.ktor.ktor.client.mock)
            }
        }

        val appleMain = create("appleMain") {
            dependsOn(commonMain)
            dependencies {
                api(libs.ionspin.bignum)
                api(libs.cash.sqldelight.native.driver)
                api(libs.ktor.ktor.client.darwin)
            }
        }

        val iosMain = create("iosMain") {
            dependsOn(appleMain)
            dependencies {
                api(libs.html.text)
            }
        }

        getByName("iosArm64Main") {
            dependsOn(iosMain)
        }

        getByName("iosSimulatorArm64Main") {
            dependsOn(iosMain)
        }

        val macosMain = create("macosMain") {
            dependsOn(appleMain)
        }

        getByName("macosArm64Main") {
            dependsOn(macosMain)
        }

        val iosTest = create("iosTest") {
            dependsOn(commonTest)
            dependencies {
                implementation(libs.ktor.ktor.client.mock)
            }
        }

        getByName("iosArm64Test") {
            dependsOn(iosTest)
        }

        getByName("iosSimulatorArm64Test") {
            dependsOn(iosTest)
        }

        getByName("macosArm64Test") {
            dependsOn(commonTest)
        }

        getByName("androidHostTest") {
            dependsOn(jvmTest)
            kotlin.srcDir("src/androidUnitTest/kotlin")
        }

        getByName("desktopTest") {
            dependsOn(jvmTest)
            dependencies {
                // The backup tests inspect the archives the repository wrote
                // with zip4j's own reader, independently of `util/zip`.
                implementation(libs.lingala.zip4j)
            }
        }

        // Share jvm code between different JVM platforms, see:
        // https://youtrack.jetbrains.com/issue/KT-28194
        // for a proper implementation.
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
            dependencies {
                api(libs.html.text)
                implementation(libs.kdrag0n.colorkt)
                implementation(libs.kyant0.m3color)
                implementation(libs.commons.codec)
                implementation(libs.halilibo.richtext.ui.material3)
                implementation(libs.halilibo.richtext.commonmark)
                implementation(libs.halilibo.richtext.markdown)
                implementation(libs.mm2d.touchicon)
                implementation(libs.google.zxing.core)
                implementation(project.dependencies.platform(libs.squareup.okhttp.bom))
                implementation(libs.squareup.okhttp)
                implementation(libs.squareup.logging.interceptor)
                api(libs.ktor.ktor.client.okhttp)
            }
        }

        getByName("desktopMain") {
            dependsOn(jvmMain)
            dependencies {
                implementation(libs.icu4j)
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.google.zxing.javase)
                implementation(libs.harawata.appdirs)
                implementation(libs.directory.watcher)
                implementation(libs.commons.lang3)
                implementation(libs.java.jna)
                val sqldelight = libs.cash.sqldelight.sqlite.driver.get()
                    .let { "${it.module}:${it.versionConstraint.requiredVersion}" }
                api(sqldelight) {
                    exclude(group = "org.xerial")
                }
                api(libs.mayakapps.window.styler)
                api(libs.vinceglb.filekit.core)
                api(libs.vinceglb.filekit.dialogs)
                api(libs.vinceglb.filekit.compose)
                api(libs.willena.sqlite.jdbc)
                api(project(":desktopLibJvm"))
            }
        }
        getByName("androidMain") {
            dependsOn(jvmMain)
            dependencies {
                api(project(":androidLibAutofill"))
                api(project.dependencies.platform(libs.firebase.bom.get()))
                api(libs.firebase.analytics)
                api(libs.firebase.crashlytics)
                api(libs.achep.bindin)
                api(libs.androidx.activity.compose)
                api(libs.androidx.appcompat)
                api(libs.androidx.autofill)
                api(libs.androidx.biometric)
                api(libs.androidx.browser)
                api(libs.androidx.core.ktx)
                api(libs.androidx.core.splashscreen)
                api(libs.androidx.core.shortcuts)
                api(libs.androidx.credentials)
                api(libs.androidx.credentials.providerevents)
                api(libs.androidx.datastore)
                api(libs.androidx.lifecycle.livedata.ktx)
                api(libs.androidx.lifecycle.process)
                api(libs.androidx.lifecycle.runtime.ktx)
                api(libs.androidx.lifecycle.viewmodel.ktx)
                api(libs.androidx.room.ktx)
                api(libs.androidx.room.runtime)
                api(libs.androidx.security.crypto.ktx)
                api(libs.androidx.work.runtime)
                api(libs.androidx.work.runtime.ktx)
                api(libs.androidx.profileinstaller)
                api(libs.android.billing.ktx)
                api(libs.android.billing)
                api(libs.google.accompanist.drawablepainter)
                api(libs.androidx.wear.remote.interactions)
                api(libs.google.play.services.wearable)
                api(libs.google.accompanist.permissions)
                api(libs.google.play.review.ktx)
                api(libs.google.play.services.base)
                api(project.dependencies.platform(libs.squareup.okhttp.bom))
                api(libs.squareup.okhttp)
                api(libs.squareup.logging.interceptor)
                api(libs.sqlcipher.android)
                api(libs.kotlinx.coroutines.android)
                implementation(libs.koin.android)
                api(libs.yubico.yubikit.android)
                api(libs.yubico.yubikit.yubiotp)
                api(libs.cash.sqldelight.android.driver)
                api(libs.osipxd.security.crypto.datastore.preferences)
                api(libs.fredporciuncula.flow.preferences)
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.artemchep.keyguard.res"
    generateResClass = always
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

// Generate KSP code for the common code:
// https://github.com/google/ksp/issues/567
val compileKotlinRegex = "^compile.*(Android|Kotlin).*".toRegex()
val kspKotlinRegex = "^ksp.*(Android|Kotlin).*".toRegex()
tasks.configureEach {
    val kspCommonTaskName = "kspCommonMainKotlinMetadata"
    if (kspCommonTaskName == name) {
        return@configureEach
    }
    if (compileKotlinRegex.matches(name) || kspKotlinRegex.matches(name)) {
        dependsOn(kspCommonTaskName)
    }
}
kotlin.compilerOptions.freeCompilerArgs.addAll(
    "-P",
    "plugin:org.jetbrains.kotlin.parcelize:additionalAnnotation=com.artemchep.keyguard.platform.parcelize.LeParcelize",
)
kotlin.sourceSets.commonMain {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

val desktopTestTask = tasks.named<Test>("desktopTest")

desktopTestTask.configure {
    filter {
        excludeTestsMatching("com.artemchep.keyguard.feature.home.vault.search.benchmark.*")
        excludeTestsMatching("com.artemchep.keyguard.crypto.benchmark.*")
        excludeTestsMatching("com.artemchep.keyguard.provider.bitwarden.usecase.benchmark.*")
        excludeTestsMatching("com.artemchep.keyguard.common.service.tld.impl.benchmark.*")
        excludeTestsMatching("com.artemchep.keyguard.common.usecase.impl.benchmark.*")
    }
}

val vaultSearchBenchmarkProperties = listOf(
    "keyguard.vault-search.benchmark.warmup-iterations",
    "keyguard.vault-search.benchmark.measurement-iterations",
)

registerJvmBenchmark(
    "vaultSearchBenchmark",
    "Runs the vault search JVM benchmark suite from desktopTest.",
    "com.artemchep.keyguard.feature.home.vault.search.benchmark.*",
) {
    forwardSystemProperties(vaultSearchBenchmarkProperties)
    benchmarkReport(
        "keyguard.vault-search.benchmark.output",
        layout.buildDirectory.file("reports/vault-search/benchmark.csv"),
        clearExisting = true,
    )
}

registerJvmBenchmark(
    "vaultSearchProfile",
    "Profiles Vault search CPU and allocation pressure with Java Flight Recorder.",
    "com.artemchep.keyguard.feature.home.vault.search.benchmark.*",
) {
    forwardSystemProperties(vaultSearchBenchmarkProperties)
    benchmarkReport(
        "keyguard.vault-search.benchmark.output",
        layout.buildDirectory.file("reports/vault-search/profile-benchmark.csv"),
        clearExisting = true,
    )
    flightRecorder(layout.buildDirectory.file("reports/vault-search/vault-search-profile.jfr"), clearExisting = true)
}

registerJvmBenchmark(
    "bitwardenCryptoBenchmark",
    "Runs the Bitwarden BC-vs-native crypto JVM benchmark suite from desktopTest.",
    "com.artemchep.keyguard.crypto.benchmark.*",
)
registerJvmBenchmark(
    "cipherSnapshotBenchmark",
    "Runs the cipher snapshot loading JVM benchmark suite from desktopTest.",
    "com.artemchep.keyguard.provider.bitwarden.usecase.benchmark.*",
)
registerJvmBenchmark(
    "tldServiceBenchmark",
    "Runs the TLD service JVM benchmark suite from desktopTest.",
    "com.artemchep.keyguard.common.service.tld.impl.benchmark.*",
)

val watchtowerBenchmarkProperties = listOf(
    "keyguard.watchtower.benchmark.case",
    "keyguard.watchtower.benchmark.corpus-size",
    "keyguard.watchtower.benchmark.service-count",
    "keyguard.watchtower.benchmark.warmup-iterations",
    "keyguard.watchtower.benchmark.measurement-iterations",
)

registerJvmBenchmark(
    "watchtowerBenchmark",
    "Benchmarks every Watchtower check on the JVM.",
    "com.artemchep.keyguard.common.usecase.impl.benchmark.WatchtowerBenchmarkTest",
) {
    forwardSystemProperties(watchtowerBenchmarkProperties)
    benchmarkReport("keyguard.watchtower.benchmark.output", layout.buildDirectory.file("reports/watchtower/benchmark.csv"))
}

registerJvmBenchmark(
    "watchtowerProfile",
    "Profiles every Watchtower check and writes a Java Flight Recorder capture.",
    "com.artemchep.keyguard.common.usecase.impl.benchmark.WatchtowerBenchmarkTest",
) {
    forwardSystemProperties(watchtowerBenchmarkProperties)
    benchmarkReport("keyguard.watchtower.benchmark.output", layout.buildDirectory.file("reports/watchtower/profile-benchmark.csv"))
    flightRecorder(layout.buildDirectory.file("reports/watchtower/watchtower-profile.jfr"))
}

val cipherUrlCheckBenchmarkProperties = listOf(
    "keyguard.cipher-url-check.benchmark.case",
    "keyguard.cipher-url-check.benchmark.operation-count",
    "keyguard.cipher-url-check.benchmark.warmup-iterations",
    "keyguard.cipher-url-check.benchmark.measurement-iterations",
)

registerJvmBenchmark(
    "cipherUrlCheckBenchmark",
    "Benchmarks every CipherUrlCheckImpl match mode on diverse JVM inputs.",
    "com.artemchep.keyguard.common.usecase.impl.benchmark.CipherUrlCheckBenchmarkTest",
) {
    forwardSystemProperties(cipherUrlCheckBenchmarkProperties)
    benchmarkReport("keyguard.cipher-url-check.benchmark.output", layout.buildDirectory.file("reports/cipher-url-check/benchmark.csv"))
}

registerJvmBenchmark(
    "cipherUrlCheckProfile",
    "Profiles CipherUrlCheckImpl and writes a Java Flight Recorder capture.",
    "com.artemchep.keyguard.common.usecase.impl.benchmark.CipherUrlCheckBenchmarkTest",
) {
    forwardSystemProperties(cipherUrlCheckBenchmarkProperties)
    benchmarkReport("keyguard.cipher-url-check.benchmark.output", layout.buildDirectory.file("reports/cipher-url-check/profile-benchmark.csv"))
    flightRecorder(layout.buildDirectory.file("reports/cipher-url-check/cipher-url-check-profile.jfr"))
}

// See:
// https://kotlinlang.org/docs/ksp-multiplatform.html#compilation-and-processing
dependencies {
    // Adds KSP generated code to the common module, therefore
    // to each of the platform.
    add("kspCommonMainMetadata", libs.arrow.arrow.optics.ksp.plugin)

    add("kspAndroid", libs.androidx.room.compiler)
    add("coreLibraryDesugaring", libs.android.desugarjdklibs)
}

enum class BuildType {
    DEV,
    RELEASE,
}

buildkonfig {
    packageName = "com.artemchep.keyguard.build"

    defaultConfigs {
        buildConfigField(STRING, "buildType", BuildType.DEV.name)
        buildConfigField(STRING, "buildDate", versionInfo.buildDate)
        buildConfigField(STRING, "buildRef", versionInfo.buildRef)
        buildConfigField(STRING, "versionName", versionInfo.marketingVersion)
        buildConfigField(INT, "versionCode", versionInfo.logicalVersion.toString())
    }
    defaultConfigs("release") {
        buildConfigField(STRING, "buildType", BuildType.RELEASE.name)
    }
}

sqldelight {
    val srcDirPrefix = "src/commonMain"
    databases {
        create("Database") {
            packageName.set("com.artemchep.keyguard.data")
            srcDirs.setFrom("$srcDirPrefix/sqldelight")
        }

        // This is a database that we use to pull data from to offer autofill suggestions
        // before a user has unlocked the vault.
        create("DatabaseExposed") {
            packageName.set("com.artemchep.keyguard.dataexposed")
            srcDirs.setFrom("$srcDirPrefix/sqldelightExposed")
        }
    }
    linkSqlite.set(false)
}

// The common source set contains KSP output. Keep ktlint's read of that source ordered after
// generation so Gradle can validate the task graph deterministically.
tasks.named {
    it == "runKtlintCheckOverCommonMainSourceSet" ||
        it == "runKtlintFormatOverCommonMainSourceSet"
}.configureEach {
    mustRunAfter("kspCommonMainKotlinMetadata")
}

// Preserve the existing SSH dependency-check entry point used by CI.
tasks.register("checkSshjDependencies") {
    group = "verification"
    description = "Compatibility alias for the root crypto dependency policy."
    dependsOn("checkBouncyCastleProductionDependencies")
}
