import com.android.build.api.dsl.BuildType
import com.artemchep.keyguard.buildplugins.version.createVersionInfo
import java.io.File
import java.util.*

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.plugin.parcelize)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.google.services)
    alias(libs.plugins.crashlytics)
    alias(libs.plugins.baseline.profile)
    id("keyguard.resources-common") apply false
}

fun loadProps(fileName: String): Properties {
    val props = Properties()
    val propsFile: File = file(fileName)
    if (propsFile.isFile) {
        propsFile.inputStream().use(props::load)
    }
    return props
}

val versionInfo = createVersionInfo(
    marketingVersion = libs.versions.appVersionName.get(),
    logicalVersion = libs.versions.appVersionCode.get().toInt(),
)

val qaSigningProps = loadProps("keyguard-qa.properties")
val releaseSigningProps = loadProps("keyguard-release.properties")
val nativeCryptoMinifiedSmoke = providers.gradleProperty("keyguard.nativeCrypto.minifiedSmoke")
    .map(String::toBoolean)
    .orElse(false)

android {
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    ndkVersion = libs.versions.androidNdk.get()
    namespace = "com.artemchep.keyguard"

    defaultConfig {
        applicationId = "com.artemchep.keyguard"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()

        versionCode = versionInfo.logicalVersion
        versionName = versionInfo.marketingVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The following argument makes the Android Test Orchestrator run its
        // "pm clear" command after each test invocation. This command ensures
        // that the app's state is completely cleared between tests.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        if (nativeCryptoMinifiedSmoke.get()) {
            testBuildType = "nativeCryptoSmokeRelease"
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        maybeCreate("debug").apply {
            keyAlias = qaSigningProps.getProperty("key_alias")
            keyPassword = qaSigningProps.getProperty("password_store")
            storeFile = file("keyguard-qa.keystore")
            storePassword = qaSigningProps.getProperty("password_key")
        }
        maybeCreate("release").apply {
            keyAlias = releaseSigningProps.getProperty("key_alias")
            keyPassword = releaseSigningProps.getProperty("password_store")
            storeFile = file("keyguard-release.keystore")
            storePassword = releaseSigningProps.getProperty("password_key")
        }
    }

    buildTypes {
        fun BuildType.applyMinification() {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../common/proguard-rules.pro",
                "proguard-rules.pro",
            )
        }

        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            applyMinification()
        }
        val releaseBuildType = getByName("release")
        create("benchmarkRelease") {
            signingConfig = signingConfigs.getByName("debug")
        }
        create("nonMinifiedRelease") {
            signingConfig = signingConfigs.getByName("debug")
        }
        if (nativeCryptoMinifiedSmoke.get()) {
            // Internal instrumentation-only target: leave production release
            // signing untouched while exercising the same shrinking rules.
            create("nativeCryptoSmokeRelease") {
                initWith(releaseBuildType)
                signingConfig = signingConfigs.getByName("debug")
                matchingFallbacks += listOf("release")
                proguardFile("native-crypto-smoke-app-rules.pro")
                testProguardFiles("native-crypto-smoke-test-rules.pro")
            }
        }
    }

    val accountManagementDimension = "accountManagement"
    flavorDimensions += accountManagementDimension
    productFlavors {
        maybeCreate("playStore").apply {
            dimension = accountManagementDimension
            buildConfigField("boolean", "ANALYTICS", "true")
        }
        maybeCreate("none").apply {
            dimension = accountManagementDimension
            buildConfigField("boolean", "ANALYTICS", "false")
        }
    }
}

androidComponents {
    listOf(
        "benchmarkRelease",
        "nonMinifiedRelease",
    ).forEach { buildType ->
        onVariants(selector().withBuildType(buildType)) { variant ->
            variant.sources.kotlin?.addStaticSourceDirectory(
                "src/benchmarkShared/kotlin",
            )
            variant.sources.manifests.addStaticManifestFile(
                "src/benchmarkShared/AndroidManifest.xml",
            )
        }
    }
    onVariants(selector().withBuildType("benchmarkRelease")) { variant ->
        variant.sources.manifests.addStaticManifestFile(
            "src/benchmarkRelease/AndroidManifest.xml",
        )
    }
}

dependencies {
    implementation(project(":common"))
    baselineProfile(project(":androidBenchmark"))
    coreLibraryDesugaring(libs.android.desugarjdklibs)

    // Unit tests
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)

    // Android tests
    androidTestImplementation(project(":androidTest"))
    androidTestImplementation(project(":util:crypto"))
    androidTestImplementation(libs.androidx.arch.core.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.web)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestUtil(libs.androidx.test.orchestrator)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    compilerOptions {
        optIn.add("androidx.compose.material.ExperimentalMaterialApi")
        val args = listOf(
            "-Xexpect-actual-classes",
        )
        freeCompilerArgs.addAll(args)
    }
}
