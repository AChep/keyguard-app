import java.io.FileInputStream
import java.util.*

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
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
    val file = file(fileName)
    if (file.exists()) {
        var stream: FileInputStream? = null
        try {
            stream = file.inputStream()
            props.load(stream)
        } finally {
            stream?.close()
        }
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

        kotlinOptions {
            freeCompilerArgs += listOf(
                "-opt-in=androidx.compose.material.ExperimentalMaterialApi",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    // previous-compilation-data.bin is Gradle's internal machinery for the incremental compilation
    // that seemed to be packed into the resulting artifact because the lib is depending directly
    // on the compilation task's output for JPMS/Multi-Release JAR support.
    //
    // > A failure occurred while executing com.android.build.gradle.internal.tasks.MergeJavaResWorkAction
    //   > 2 files found with path 'META-INF/versions/9/previous-compilation-data.bin' from inputs:
    //     - /home/runner/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-datetime-jvm/0.4.1/684eec210b21e2da7382a4aa85e56fb7b71f39b3/kotlinx-datetime-jvm-0.4.1.jar
    //     - /home/runner/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/atomicfu-jvm/0.22.0/c6a128a44ba52a18265e5ec816130cd341d80792/atomicfu-jvm-0.22.0.jar
    packagingOptions {
        resources.excludes.add("META-INF/versions/9/previous-compilation-data.bin")
        resources.excludes.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
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
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
}
