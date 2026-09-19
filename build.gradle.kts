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

//
// The custom keyguard Detekt rules from :detektRules
//
// They get dedicated tasks rather than riding along on the shared `detekt` task, which runs
// without an analysis classpath and so silently skips rules that need type resolution, and whose
// baselines must never suppress a hand-written project invariant. Modules opt in by applying
// `keyguard.detekt-custom-rules` and registering the compilations to analyse.
//

val customRuleModules = listOf(
    ":androidLibAutofill",
    ":common",
    ":integration:androidIpcTestClient",
    ":util:kdbx",
    ":util:webdav",
    ":wearApp",
)

// The APIs guarded by the custom rules, shared with every opted-in module's coverage check.
val guardedApiMarkers =
    com.artemchep.keyguard.buildplugins.detekt.DetektCustomRulesPlugin.GUARDED_API_MARKERS

// Catches a module that starts using a guarded API without opting into the custom-rule tasks.
val verifyDetektCustomRulesOwnership = tasks.register(
    "verifyDetektCustomRulesOwnership",
    com.artemchep.keyguard.buildplugins.detekt.VerifyDetektMarkerCoverageTask::class,
) {
    group = "verification"
    description = "Fails if a guarded API is used outside the modules that run the custom " +
        "Detekt rules."
    markers.set(guardedApiMarkers)
    rootDirectory.set(layout.projectDirectory)
    candidateFiles.from(
        fileTree(layout.projectDirectory) {
            include("**/src/**/*.kt")
            exclude("**/build/**")
        },
    )
    // Ownership is decided purely from the path prefixes: per-module coverage is verified
    // inside each module, so nothing is registered as "analysed" here.
    expectsAnalysedSources.set(false)
    allowedPathPrefixes.set(
        customRuleModules.map { "${it.removePrefix(":").replace(':', '/')}/src" } +
            // The rules, their fixtures and the convention plugin name the guarded APIs as the
            // thing they enforce rather than calling them.
            listOf("detektRules/src", "buildPlugins/src"),
    )
    stamp.set(layout.buildDirectory.file("reports/detekt/custom-rules-ownership.txt"))
}

tasks.register("detektCustomRules") {
    group = "verification"
    description = "Runs every custom keyguard Detekt rule across the repository."
    dependsOn(verifyDetektCustomRulesOwnership)
    dependsOn(customRuleModules.map { "$it:detektCustomRules" })
}
