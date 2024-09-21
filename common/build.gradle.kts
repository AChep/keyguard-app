import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.INT
import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING
import org.jetbrains.kotlin.daemon.common.toHexString
import java.security.MessageDigest
import java.util.*

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.plugin.parcelize)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.buildkonfig)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
}

//
// Obtain the build configuration
//

val versionInfo = createVersionInfo(
    marketingVersion = libs.versions.appVersionName.get(),
    logicalVersion = libs.versions.appVersionCode.get().toInt(),
)

// We want to know when the public data files
// change. For example we might need to re-compute
// watchtower alerts in that case.
val generateResHashesTask = tasks.register("generateKeyguardResHashes") {
    val packageName = "com.artemchep.keyguard.build"

    val prefix = "src/commonMain/composeResources/files"
    val src = files(
        "$prefix/justdeleteme.json",
        "$prefix/justgetmydata.json",
        "$prefix/passkeys.json",
        "$prefix/public_suffix_list.txt",
        "$prefix/tfa.json",
    )
    src.forEach { file ->
        inputs.files(file)
    }
    val out = file("build/generated/keyguardResHashes/kotlin/")
    outputs.dir(out)

    fun File.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = readBytes()
        return md
            .digest(bytes)
            .toHexString()
    }

    doFirst {
        val payload = src
            .map { file ->
                val name = file.name
                    .substringBefore('.')
                val hash = file.md5()
                name to hash
            }
            .map { (name, hash) ->
                "const val $name = \"$hash\""
            }
            .joinToString(separator = "\n")
        val content = """
package $packageName

data object FileHashes {
$payload
}
        """.trimIndent()
        out
            .resolve("FileHashes.kt")
            .writeText(content)
    }
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).all {
    dependsOn(generateResHashesTask)
}

kotlin {
    androidTarget()
    jvm("desktop")

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
        commonMain {
            kotlin.srcDir(generateResHashesTask.get().outputs.files)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                api(compose.components.resources)

                api(libs.kdrag0n.colorkt)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.collections.immutable)
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.serialization.cbor)
                api(libs.kotlinx.serialization.protobuf)
                api(libs.arrow.arrow.core)
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
                api(libs.cash.sqldelight.coroutines.extensions)
                api(libs.halilibo.richtext.ui.material3)
                api(libs.halilibo.richtext.commonmark)
                api(libs.halilibo.richtext.markdown)
                api(libs.devsrsouza.feather)
            }
        }

        // Share jvm code between different JVM platforms, see:
        // https://youtrack.jetbrains.com/issue/KT-28194
        // for a proper implementation.
        val jvmMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.lingala.zip4j)
                implementation(libs.nulabinc.zxcvbn)
                implementation(libs.commons.codec)
                implementation(libs.bouncycastle.bcpkix)
                implementation(libs.bouncycastle.bcprov)
                implementation(libs.ricecode.string.similarity)
                implementation(libs.google.zxing.core)
                // SignalR
                implementation(libs.microsoft.signalr)
                implementation(libs.microsoft.signalr.messagepack)
                // ...implicitly added by SignalR, so we might as well opt-in
                // for the latest and 'best-est' version.
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
                implementation(libs.commons.lang3)
                val sqldelight = libs.cash.sqldelight.sqlite.driver.get()
                    .let { "${it.module}:${it.versionConstraint.requiredVersion}" }
                api(sqldelight) {
                    exclude(group = "org.xerial")
                }
                api(libs.kamel.image)
                api(libs.mayakapps.window.styler)
                api(libs.wunderbox.nativefiledialog)
                api(libs.willena.sqlite.jdbc)
            }
        }
        val androidMain by getting {
            dependsOn(jvmMain)
            dependencies {
                api(platform(libs.firebase.bom.get()))
                api(libs.firebase.analytics.ktx)
                api(libs.firebase.crashlytics.ktx)
                api(libs.achep.bindin)
                api(libs.androidx.activity.compose)
                api(libs.androidx.appcompat)
                api(libs.androidx.autofill)
                api(libs.androidx.biometric.ktx)
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
                api(libs.glide.annotations)
                api(libs.glide.glide)
                api(libs.landscapist.glide)
                api(libs.landscapist.placeholder)
                api(libs.google.accompanist.drawablepainter)
                api(libs.google.accompanist.navigation.material)
                api(libs.google.accompanist.systemuicontroller)
                api(libs.google.accompanist.permissions)
                api(libs.google.play.review.ktx)
                api(libs.google.play.services.base)
                api(libs.google.play.services.mlkit.barcode.scanning)
                api(libs.squareup.okhttp)
                api(libs.squareup.logging.interceptor)
                api(libs.ktor.ktor.client.okhttp)
                api(libs.mm2d.touchicon.http.okhttp)
                api(libs.mm2d.touchicon)
                api(libs.sqlcipher.android)
                api(libs.kotlinx.coroutines.android)
                api(libs.kodein.kodein.di.framework.android.x.viewmodel.savedstate)
                api(libs.html.text)
                api(libs.yubico.yubikit.android)
                api(libs.cash.sqldelight.android.driver)
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

android {
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    namespace = "com.artemchep.keyguard.common"

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    val accountManagementDimension = "accountManagement"
    flavorDimensions += accountManagementDimension
    productFlavors {
        maybeCreate("playStore").apply {
            dimension = accountManagementDimension
            buildConfigField("boolean", "ANALYTICS", "false")
        }
        maybeCreate("none").apply {
            dimension = accountManagementDimension
            buildConfigField("boolean", "ANALYTICS", "false")
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

// Generate KSP code for the common code:
// https://github.com/google/ksp/issues/567
tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>>().all {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
kotlin.compilerOptions {
    freeCompilerArgs.add("-Xcontext-receivers")
}
kotlin.compilerOptions.freeCompilerArgs.addAll(
    "-P",
    "plugin:org.jetbrains.kotlin.parcelize:additionalAnnotation=com.artemchep.keyguard.platform.parcelize.LeParcelize",
)
kotlin.sourceSets.commonMain {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

// TODO: Fix a problem with Moko+KtLint:
//  https://github.com/icerockdev/moko-resources/issues/421

// See:
// https://kotlinlang.org/docs/ksp-multiplatform.html#compilation-and-processing
dependencies {
    // Adds KSP generated code to the common module, therefore
    // to each of the platform.
    add("kspCommonMainMetadata", libs.arrow.arrow.optics.ksp.plugin)

    add("kspAndroid", libs.androidx.room.compiler)
    add("kspAndroid", libs.glide.ksp)
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
        buildConfigField(STRING, "versionName", versionInfo.marketingVersion.toString())
        buildConfigField(INT, "versionCode", versionInfo.logicalVersion.toString())
    }
    defaultConfigs("release") {
        buildConfigField(STRING, "buildType", BuildType.RELEASE.name)
    }
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("com.artemchep.keyguard.data")
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
