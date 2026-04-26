import com.android.build.api.dsl.BuildType
import com.artemchep.keyguard.buildplugins.version.createVersionInfo
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.google.services)
    alias(libs.plugins.crashlytics)
    id("keyguard.resources-common") apply false
}

fun loadProps(file: File): Properties {
    val props = Properties()
    if (file.isFile) {
        file.inputStream().use(props::load)
    }
    return props
}

fun keystoreFile(name: String) =
    file(name)

val versionInfo = createVersionInfo(
    marketingVersion = libs.versions.appVersionName.get(),
    logicalVersion = libs.versions.appVersionCode.get().toInt(),
)

val qaSigningProps = loadProps(keystoreFile("keyguard-qa.properties"))
val releaseSigningProps = loadProps(keystoreFile("keyguard-release.properties"))

android {
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    namespace = "com.artemchep.keyguard"

    defaultConfig {
        applicationId = "com.artemchep.keyguard"
        minSdk = 30
        targetSdk = libs.versions.androidTargetSdk.get().toInt()

        versionCode = versionInfo.logicalVersion
        versionName = versionInfo.marketingVersion

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
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
            storeFile = keystoreFile("keyguard-qa.keystore")
            storePassword = qaSigningProps.getProperty("password_key")
        }
        maybeCreate("release").apply {
            keyAlias = releaseSigningProps.getProperty("key_alias")
            keyPassword = releaseSigningProps.getProperty("password_store")
            storeFile = keystoreFile("keyguard-release.keystore")
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
    coreLibraryDesugaring(libs.android.desugarjdklibs)

    implementation(libs.jetbrains.compose.material3)
    implementation(libs.jetbrains.compose.ui.tooling.preview)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.remote.interactions)
    implementation(libs.horologist.compose.layout)
    debugImplementation(libs.jetbrains.compose.ui.tooling)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    metricsDestination = layout.buildDirectory.dir("compose-metrics")
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
        )
    }
}
