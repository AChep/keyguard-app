import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec

// This is necessary to avoid the plugins to be loaded multiple times
// in each subproject's classloader.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baseline.profile) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.kotlin.plugin.parcelize) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.crashlytics) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.buildkonfig) apply false
    alias(libs.plugins.license.check) apply false
    alias(libs.plugins.versions) apply true
    alias(libs.plugins.version.catalog.update) apply true
    id("keyguard.ktlint")
    id("keyguard.crypto-dependency-policy")
}

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt())
    // We use Jetbrains distribution because it contains many fixes
    // for AWT. For example, with the other distributions I can not
    // make a POPUP window and have an input field in it.
    vendor = JvmVendorSpec.JETBRAINS
}

// Xcode reads this committed configuration before running any build scripts.
// Regenerate it when appVersionName changes; CI checks it without rewriting it.
val appleVersionConfig = layout.projectDirectory.file("xcode/Version.xcconfig")
val appleVersionConfigContent =
    libs.versions.appVersionName.map { version ->
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
            "Apple marketing versions must use major.minor.patch; appVersionName is '$version'."
        }
        "// Generated from gradle/libs.versions.toml. Do not edit.\n" +
            "// Regenerate with ./gradlew generateAppleVersion.\n" +
            "MARKETING_VERSION = $version\n"
    }

tasks.register("generateAppleVersion") {
    group = "build setup"
    description = "Updates the shared Apple marketing version from the version catalog."
    val config = appleVersionConfig
    val content = appleVersionConfigContent
    inputs.property("content", content)
    outputs.file(config)
    doLast {
        config.asFile.writeText(content.get())
    }
}

tasks.register("checkAppleVersion") {
    group = "verification"
    description = "Checks that the committed Apple marketing version matches the version catalog."
    // Order an explicitly requested generation first, but never regenerate during a check.
    mustRunAfter("generateAppleVersion")
    val config = appleVersionConfig
    val content = appleVersionConfigContent
    inputs.files(config).withPathSensitivity(PathSensitivity.NONE)
    inputs.property("content", content)
    doLast {
        check(config.asFile.isFile && config.asFile.readText() == content.get()) {
            "xcode/Version.xcconfig is missing or stale. Run ./gradlew generateAppleVersion " +
                "and commit the updated file."
        }
    }
}

fun validateAppleDeploymentTarget(
    name: String,
    version: String,
): String {
    require(version.matches(Regex("[0-9]+\\.[0-9]+"))) {
        "Apple deployment targets must use major.minor; $name is '$version'."
    }
    return version
}

fun appleDeploymentConfigContent(
    macosVersion: String,
    iosVersion: String,
): String {
    val macos = validateAppleDeploymentTarget("appleMacosDeploymentTarget", macosVersion)
    val ios = validateAppleDeploymentTarget("appleIosDeploymentTarget", iosVersion)
    return "// Generated from gradle/libs.versions.toml. Do not edit.\n" +
        "// Regenerate with ./gradlew generateAppleConfiguration.\n" +
        "MACOSX_DEPLOYMENT_TARGET = $macos\n" +
        "IPHONEOS_DEPLOYMENT_TARGET = $ios\n"
}

fun updateSwiftPackageDeploymentTargets(
    source: String,
    macosVersion: String,
    iosVersion: String,
): String {
    fun replaceSingle(
        input: String,
        pattern: Regex,
        replacement: String,
        platform: String,
    ): String {
        val matches = pattern.findAll(input).toList()
        check(matches.size == 1) {
            "Expected exactly one $platform platform declaration in appleUi/Package.swift, " +
                "found ${matches.size}."
        }
        return input.replaceRange(matches.single().range, replacement)
    }

    val macos = validateAppleDeploymentTarget("appleMacosDeploymentTarget", macosVersion)
    val ios = validateAppleDeploymentTarget("appleIosDeploymentTarget", iosVersion)
    return replaceSingle(
        input = source,
        pattern = Regex("""\.macOS\("[^"]+"\)"""),
        replacement = ".macOS(\"$macos\")",
        platform = "macOS",
    ).let { updated ->
        replaceSingle(
            input = updated,
            pattern = Regex("""\.iOS\("[^"]+"\)"""),
            replacement = ".iOS(\"$ios\")",
            platform = "iOS",
        )
    }
}

val appleDeploymentConfig = layout.projectDirectory.file("xcode/DeploymentTargets.xcconfig")
val appleUiPackageManifest = layout.projectDirectory.file("appleUi/Package.swift")
val appleMacosDeploymentTarget = libs.versions.appleMacosDeploymentTarget
val appleIosDeploymentTarget = libs.versions.appleIosDeploymentTarget

tasks.register("generateAppleDeploymentTargets") {
    group = "build setup"
    description = "Updates Apple deployment targets from the version catalog."
    val config = appleDeploymentConfig
    val packageManifest = appleUiPackageManifest
    val macosVersion = appleMacosDeploymentTarget
    val iosVersion = appleIosDeploymentTarget
    inputs.property("macosDeploymentTarget", macosVersion)
    inputs.property("iosDeploymentTarget", iosVersion)
    outputs.file(config)
    outputs.file(packageManifest)
    doLast {
        val macos = macosVersion.get()
        val ios = iosVersion.get()
        config.asFile.writeText(appleDeploymentConfigContent(macos, ios))
        val packageSource = packageManifest.asFile.readText()
        packageManifest.asFile.writeText(
            updateSwiftPackageDeploymentTargets(packageSource, macos, ios),
        )
    }
}

tasks.register("checkAppleDeploymentTargets") {
    group = "verification"
    description = "Checks Apple deployment targets against the version catalog."
    mustRunAfter("generateAppleDeploymentTargets")
    val config = appleDeploymentConfig
    val packageManifest = appleUiPackageManifest
    val macosVersion = appleMacosDeploymentTarget
    val iosVersion = appleIosDeploymentTarget
    inputs.files(config, packageManifest).withPathSensitivity(PathSensitivity.NONE)
    inputs.property("macosDeploymentTarget", macosVersion)
    inputs.property("iosDeploymentTarget", iosVersion)
    doLast {
        val macos = macosVersion.get()
        val ios = iosVersion.get()
        val expectedConfig = appleDeploymentConfigContent(macos, ios)
        check(config.asFile.isFile && config.asFile.readText() == expectedConfig) {
            "xcode/DeploymentTargets.xcconfig is missing or stale. Run " +
                "./gradlew generateAppleConfiguration and commit the updated file."
        }
        val packageSource = packageManifest.asFile.readText()
        check(packageSource == updateSwiftPackageDeploymentTargets(packageSource, macos, ios)) {
            "appleUi/Package.swift has stale deployment targets. Run " +
                "./gradlew generateAppleConfiguration and commit the updated file."
        }
    }
}

tasks.register("generateAppleConfiguration") {
    group = "build setup"
    description = "Updates generated Apple build configuration from the version catalog."
    dependsOn("generateAppleVersion", "generateAppleDeploymentTargets")
}

tasks.register("checkAppleConfiguration") {
    group = "verification"
    description = "Checks generated Apple build configuration against the version catalog."
    dependsOn("checkAppleVersion", "checkAppleDeploymentTargets")
}
