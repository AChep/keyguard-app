import com.android.build.api.dsl.BuildType
import org.gradle.kotlin.dsl.support.kotlinCompilerOptions
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

android {
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
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
        create("benchmarkRelease") {
            signingConfig = signingConfigs.getByName("debug")
        }
        create("nonMinifiedRelease") {
            signingConfig = signingConfigs.getByName("debug")
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

dependencies {
    implementation(project(":common"))
    baselineProfile(project(":androidBenchmark"))
    coreLibraryDesugaring(libs.android.desugarjdklibs)

    // Android tests
    androidTestImplementation(project(":androidTest"))
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
