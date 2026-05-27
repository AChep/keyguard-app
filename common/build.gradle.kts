import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.INT
import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING
import com.artemchep.keyguard.buildplugins.resources.ResourcesCommonExtension
import com.artemchep.keyguard.buildplugins.version.createVersionInfo
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.plugin.parcelize)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.buildkonfig)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    id("keyguard.resources-common")
}

//
// Obtain the build configuration
//

val versionInfo = createVersionInfo(
    marketingVersion = libs.versions.appVersionName.get(),
    logicalVersion = libs.versions.appVersionCode.get().toInt(),
)

keyguardResources {
    composeResourcesDir.set(ResourcesCommonExtension.defaultComposeResourcesDir(project))
    composeFilesDir.set(ResourcesCommonExtension.defaultComposeFilesDir(project))
    generatedPackageName.set(ResourcesCommonExtension.DEFAULT_PACKAGE_NAME)
    defaultLocale.set(ResourcesCommonExtension.DEFAULT_LOCALE)
    hashEntries.putAll(ResourcesCommonExtension.defaultHashEntries())
    hashKotlinOutputDir.set(ResourcesCommonExtension.defaultHashKotlinOutputDir(project))
    localeKotlinOutputDir.set(ResourcesCommonExtension.defaultLocaleKotlinOutputDir(project))
    localeResOutputDir.set(ResourcesCommonExtension.defaultLocaleResOutputDir(project))
}

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
        val commonMain by getting {
            dependencies {
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
                api(libs.kodein.kodein.di)
                api(libs.kodein.kodein.di.framework.compose)
                api(libs.androidx.lifecycle.common)
                api(libs.androidx.lifecycle.runtime)
                api(libs.androidx.lifecycle.runtime.compose)
                api(libs.ktor.ktor.client.core)
                api(libs.ktor.ktor.client.logging)
                api(libs.ktor.ktor.client.content.negotiation)
                api(libs.ktor.ktor.client.websockets)
                api(libs.ktor.ktor.serialization.kotlinx)
                api(project(":util:signalr"))
                api(libs.coil3.coil.compose)
                api(libs.coil3.coil.network.ktor3)
                api(libs.cash.sqldelight.coroutines.extensions)
                api(libs.devsrsouza.feather)
                api(libs.html.text)
                api(libs.haze.core)
                api(libs.haze.blur)
                api(libs.haze.materials)
                api(libs.ksoup.html)
                api(libs.snipme.highlights)
                api(libs.kdroidfilter.platformtools.darkmodedetector)
            }
        }
        val commonTest by getting {
            kotlin.setSrcDirs(emptyList<String>())
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val jvmTest by creating {
            dependsOn(commonTest)
            kotlin.srcDir("src/commonTest/kotlin")
        }

        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.diglol.crypto.kdf)
                api(libs.cash.sqldelight.native.driver)
                api(libs.ktor.ktor.client.darwin)
            }
        }

        val iosArm64Main by getting {
            dependsOn(iosMain)
        }

        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
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

        val androidHostTest by getting {
            dependsOn(jvmTest)
            kotlin.srcDir("src/androidUnitTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.ktor.client.mock)
            }
        }

        val desktopTest by getting {
            dependsOn(jvmTest)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.ktor.client.mock)
            }
        }

        // Share jvm code between different JVM platforms, see:
        // https://youtrack.jetbrains.com/issue/KT-28194
        // for a proper implementation.
        val jvmMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.lingala.zip4j)
                implementation(libs.kdrag0n.colorkt)
                implementation(libs.kyant0.m3color)
                implementation(libs.nulabinc.zxcvbn)
                implementation(libs.commons.codec)
                implementation(libs.bouncycastle.bcpkix)
                implementation(libs.bouncycastle.bcprov)
                implementation(libs.keemobile.kotpass)
                implementation(libs.halilibo.richtext.ui.material3)
                implementation(libs.halilibo.richtext.commonmark)
                implementation(libs.halilibo.richtext.markdown)
                implementation(libs.mm2d.touchicon)
                implementation(libs.hierynomus.sshj)
                implementation(libs.google.zxing.core)
                implementation(libs.icu4j)
                implementation(project.dependencies.platform(libs.squareup.okhttp.bom))
                implementation(libs.squareup.okhttp)
                implementation(libs.squareup.logging.interceptor)
                api(libs.ktor.ktor.client.okhttp)
            }
        }

        val desktopMain by getting {
            dependsOn(jvmMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.google.zxing.javase)
                implementation(libs.harawata.appdirs)
                implementation(libs.directory.watcher)
                implementation(libs.commons.lang3)
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
        val androidMain by getting {
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
                api(libs.androidx.datastore)
                api(libs.androidx.lifecycle.livedata.ktx)
                api(libs.androidx.lifecycle.process)
                api(libs.androidx.lifecycle.runtime.ktx)
                api(libs.androidx.lifecycle.viewmodel.ktx)
                api(libs.androidx.room.ktx)
                api(libs.androidx.room.runtime)
                api(libs.androidx.security.crypto.ktx)
                api(libs.androidx.camera.core)
                api(libs.androidx.camera.camera2)
                api(libs.androidx.camera.lifecycle)
                api(libs.androidx.camera.view)
                api(libs.androidx.camera.extensions)
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
                api(libs.google.play.services.mlkit.barcode.scanning)
                api(project.dependencies.platform(libs.squareup.okhttp.bom))
                api(libs.squareup.okhttp)
                api(libs.squareup.logging.interceptor)
                api(libs.ktor.ktor.client.okhttp)
                api(libs.sqlcipher.android)
                api(libs.kotlinx.coroutines.android)
                api(libs.kodein.kodein.di.framework.android.x.viewmodel.savedstate)
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
kotlin.compilerOptions {
    freeCompilerArgs.add("-Xcontext-parameters")
}
kotlin.compilerOptions.freeCompilerArgs.addAll(
    "-P",
    "plugin:org.jetbrains.kotlin.parcelize:additionalAnnotation=com.artemchep.keyguard.platform.parcelize.LeParcelize",
)
kotlin.sourceSets.commonMain {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

val desktopTestTask = tasks.named<Test>("desktopTest")
val desktopTestClassesTask = tasks.named("desktopTestClasses")

tasks.register<Test>("vaultSearchBenchmark") {
    group = "verification"
    description = "Runs the vault search JVM benchmark suite from desktopTest."

    dependsOn(desktopTestClassesTask)

    testClassesDirs = desktopTestTask.get().testClassesDirs
    classpath = desktopTestTask.get().classpath

    maxParallelForks = 1
    forkEvery = 0L
    outputs.upToDateWhen { false }

    systemProperty("user.language", "en")
    systemProperty("user.country", "US")

    filter {
        includeTestsMatching("com.artemchep.keyguard.feature.home.vault.search.benchmark.*")
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

// Reason: Task ':common:generateNoneReleaseLintVitalModel' uses this output of
// task ':common:copyFontsToAndroidAssets' without declaring an explicit or
// implicit dependency. This can lead to incorrect results being produced,
// depending on what order the tasks are executed.
tasks.findByName("generateNoneReleaseLintVitalModel")?.dependsOn("copyFontsToAndroidAssets")
tasks.findByName("generatePlayStoreReleaseLintVitalModel")?.dependsOn("copyFontsToAndroidAssets")
