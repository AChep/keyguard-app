package com.artemchep.keyguard.buildplugins.android

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.SigningConfig
import org.gradle.api.Project
import java.io.File
import java.util.Properties

/** Shared production defaults for the phone and Wear vault applications. */
fun ApplicationExtension.configureKeyguardApplication(project: Project) {
    ndkVersion = project.androidCatalog().findVersion("androidNdk").get().requiredVersion
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Clear the app's state between orchestrated test invocations.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        vectorDrawables.useSupportLibrary = true
    }
    enableCoreLibraryDesugaring(project)
    testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"
    project.dependencies.add(
        "androidTestUtil",
        project.androidCatalog().findLibrary("androidx-test-orchestrator").get(),
    )
    bundle.language.enableSplit = false
    buildFeatures.buildConfig = true

    signingConfigs.maybeCreate("debug").loadKeyguardSigning(project, "keyguard-qa")
    signingConfigs.maybeCreate("release").loadKeyguardSigning(project, "keyguard-release")
    buildTypes.getByName("debug").applicationIdSuffix = ".debug"
    buildTypes.getByName("release").apply {
        signingConfig = signingConfigs.getByName("release")
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            project.file("../common/proguard-rules.pro"),
            project.file("proguard-rules.pro"),
        )
    }
    accountManagementFlavors(withAnalytics = true)
}

/** Opt in without adding production signing, flavors or instrumentation policy. */
fun CommonExtension.enableCoreLibraryDesugaring(project: Project) {
    compileOptions.isCoreLibraryDesugaringEnabled = true
    project.dependencies.add(
        "coreLibraryDesugaring",
        project.androidCatalog().findLibrary("android-desugarjdklibs").get(),
    )
}

fun CommonExtension.accountManagementFlavors(withAnalytics: Boolean = false) {
    val accountManagementDimension = "accountManagement"
    flavorDimensions += accountManagementDimension
    listOf("playStore" to true, "none" to false).forEach { (name, analytics) ->
        productFlavors.maybeCreate(name).apply {
            dimension = accountManagementDimension
            if (withAnalytics) {
                buildConfigField("boolean", "ANALYTICS", analytics.toString())
            }
        }
    }
}

private fun SigningConfig.loadKeyguardSigning(project: Project, filePrefix: String) {
    val properties = loadSigningProperties(project.file("$filePrefix.properties"))
    keyAlias = properties.getProperty("key_alias")
    // Keep the existing property names: these map to key/store in this order.
    keyPassword = properties.getProperty("password_store")
    storePassword = properties.getProperty("password_key")
    storeFile = project.file("$filePrefix.keystore")
}

private fun loadSigningProperties(file: File): Properties = Properties().apply {
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}
