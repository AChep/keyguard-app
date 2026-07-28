import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.extensions.FailOnSeverity
import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec

// Top-level build file where you can add configuration options common
// to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

// This is necessary to avoid the plugins to be loaded multiple times
// in each subproject's classloader.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baseline.profile) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.kapt) apply false
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
    id("keyguard.crypto-dependency-policy")
}

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt())
    // We use Jetbrains distribution because it contains many fixes
    // for AWT. For example, with the other distributions I can not
    // make a POPUP window and have an input field in it.
    vendor = JvmVendorSpec.JETBRAINS
}

subprojects {
    // The custom rules live here, so this module can not be a Detekt consumer of itself.
    if (path == ":detektRules") {
        return@subprojects
    }

    apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)

    // Makes the `keyguard` rule set a known config key everywhere. The rules that need type
    // resolution stay inactive on these tasks and are enabled only by the dedicated tasks; see
    // the `keyguard.detekt-custom-rules` convention plugin.
    dependencies {
        add("detektPlugins", project(":detektRules"))
    }

    configure<DetektExtension> {
        toolVersion.set(rootProject.libs.versions.detekt)
        source.setFrom(
            fileTree("src") {
                include("**/*.kt")
                include("**/*.kts")
            },
        )
        config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig.set(true)
        baseline.set(
            rootProject.layout.projectDirectory.file(
                "config/detekt/baseline/${path.removePrefix(":").replace(':', '-')}.xml",
            ),
        )
        basePath.set(rootProject.layout.projectDirectory)
        ignoreFailures.set(false)
        failOnSeverity.set(FailOnSeverity.Error)
    }

    if (
        name == "androidApp" ||
        name == "wearApp" ||
        name == "desktopApp"
    ) {
        apply(plugin = rootProject.libs.plugins.license.check.get().pluginId)

        configure<app.cash.licensee.LicenseeExtension> {
            allow("Apache-2.0")
            allow("MIT")
            allow("EPL-1.0")
            allow("EPL-2.0")
            allow("CC0-1.0")
            allow("BSD-2-Clause")
            allow("BSD-3-Clause")

            //
            // Android
            //

            allowUrl("https://developer.android.com/studio/terms.html") {
                because("Android Developers")
            }
            allowUrl("https://developer.android.com/guide/playcore/license") {
                because("Android Developers")
            }
            allowUrl("https://developers.google.com/ml-kit/terms") {
                because("Google Developers")
            }

            //
            // Self-hosted
            //

            allowUrl("https://opensource.org/license/mit") {
                because("MIT License, but self-hosted copy of the license")
            }
            allowUrl("https://github.com/devsrsouza/compose-icons/blob/master/LICENSE") {
                because("MIT License, but self-hosted copy of the license")
            }
            allowUrl("https://spdx.org/licenses/MIT.txt") {
                because("MIT License, but self-hosted copy of the license")
            }
            allowUrl("https://opensource.org/licenses/MIT") {
                because("MIT License, but self-hosted copy of the license")
            }
            allowUrl("https://opensource.org/licenses/mit-license.php") {
                because("MIT License, but self-hosted copy of the license")
            }
            allowUrl("https://github.com/vinceglb/FileKit/blob/main/LICENSE") {
                because("MIT License, but self-hosted copy of the license")
            }
            allowUrl("https://github.com/hypfvieh/dbus-java/blob/master/LICENSE") {
                because("MIT License, but self-hosted copy of the license")
            }
            allowUrl("https://github.com/icerockdev/moko-resources/blob/master/LICENSE.md") {
                because("Apache License-2.0, but self-hosted copy of the license")
            }
            allowUrl("https://github.com/icerockdev/moko-graphics/blob/master/LICENSE.md") {
                because("Apache License-2.0, but self-hosted copy of the license")
            }
            allowUrl("https://github.com/icerockdev/moko-parcelize/blob/master/LICENSE.md") {
                because("Apache License-2.0, but self-hosted copy of the license")
            }
            allowUrl("https://github.com/WonderzGmbH/nativefiledialog-java/blob/master/LICENSE") {
                because("zlib License, but self-hosted copy of the license")
            }
            allowUrl("https://asm.ow2.io/license.html") {
                because("3-Clause BSD License, but self-hosted copy of the license")
            }
            allowDependency("com.github.AChep", "bindin", "1.4.0") {
                because("MIT License, but self-hosted copy of the license")
            }
            allowDependency("com.mayakapps.compose", "window-styler", "0.3.2") {
                because("MIT License, but self-hosted copy of the license")
            }
            allowDependency("com.mayakapps.compose", "window-styler-jvm", "0.3.2") {
                because("MIT License, but self-hosted copy of the license")
            }
            allowDependency("commons-logging", "commons-logging", "1.0.4") {
                because("Apache License-2.0, but self-hosted copy of the license")
            }
            allowDependency("com.github.spotbugs", "spotbugs-annotations", "4.9.8") {
                because("Static code analysis")
            }
            allowDependency("com.github.jai-imageio", "jai-imageio-core", "1.4.0") {
                // https://github.com/jai-imageio/jai-imageio-core/blob/master/LICENSE.txt
                because("Sun Microsystems, Inc")
            }
            allowDependency("com.ibm.icu", "icu4j", "73.1") {
                because("UNICODE LICENSE V3")
            }
            allowDependency("com.ibm.icu", "icu4j", "75.1") {
                because("UNICODE LICENSE V3")
            }

            //
            // Other
            //

            allowUrl("https://www.zetetic.net/sqlcipher/license/") {
                because("BDS-like License")
            }
        }
    }
}

allprojects {
    apply(plugin = rootProject.libs.plugins.ktlint.get().pluginId)

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint.get())
    }
}

//
// The custom keyguard Detekt rules from :detektRules
//
// They get dedicated tasks rather than riding along on the shared `detekt` task, which runs
// without an analysis classpath and so silently skips rules that need type resolution, and whose
// baselines must never suppress a hand-written project invariant. Modules opt in by applying
// `keyguard.detekt-custom-rules` and registering the compilations to analyse.
//

val customRuleModules = listOf(":common", ":wearApp")

// The APIs guarded by the custom rules. Each opted-in module repeats the ones it uses via
// `requireCoverageFor(...)`; this list is the repository-wide view used by the ownership check.
val guardedApiMarkers = listOf("mutablePersistedFlow")

// Catches a module that starts using a guarded API without opting into the custom-rule tasks.
val verifyDetektCustomRulesOwnership by tasks.registering(
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
