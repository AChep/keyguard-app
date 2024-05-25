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
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.crashlytics) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.buildkonfig) apply false
    alias(libs.plugins.license.check) apply false
    alias(libs.plugins.versions) apply true
    alias(libs.plugins.version.catalog.update) apply true
}

subprojects {
    if (
        name == "androidApp" ||
        name == "desktopApp"
    ) {
        apply(plugin = rootProject.libs.plugins.license.check.get().pluginId)

        configure<app.cash.licensee.LicenseeExtension> {
            allow("Apache-2.0")
            allow("MIT")
            allow("EPL-1.0")
            allow("CC0-1.0")
            allow("BSD-2-Clause")

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

            allowUrl("https://github.com/devsrsouza/compose-icons/blob/master/LICENSE") {
                because("MIT License, but self-hosted copy of the license")
            }
            allowUrl("https://www.bouncycastle.org/licence.html") {
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
            allowDependency("com.github.jai-imageio", "jai-imageio-core", "1.4.0") {
                // https://github.com/jai-imageio/jai-imageio-core/blob/master/LICENSE.txt
                because("Sun Microsystems, Inc")
            }
            allowDependency("com.ibm.icu", "icu4j", "73.1") {
                because("UNICODE LICENSE V3")
            }

            //
            // Other
            //

            allowUrl("https://www.zetetic.net/sqlcipher/license/") {
                because("BDS-like License")
            }
            allowUrl("http://www.bouncycastle.org/licence.html") {
                because("MIT-like License")
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
