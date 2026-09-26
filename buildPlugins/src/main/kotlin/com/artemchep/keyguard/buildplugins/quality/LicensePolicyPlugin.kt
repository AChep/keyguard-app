package com.artemchep.keyguard.buildplugins.quality

import app.cash.licensee.LicenseeExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class LicensePolicyPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("app.cash.licensee")
        extensions.configure<LicenseeExtension> {
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
