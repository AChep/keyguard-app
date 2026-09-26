package com.artemchep.keyguard.buildplugins.cargo

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CargoOfflineFunctionalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sharedOfflineSettingReachesGenericHelperTasksAndCanBeOverridden() {
        val root = temporaryFolder.newFolder()
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"native-helper\"")
        root.resolve("build.gradle.kts").writeText(
            """
            import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask
            plugins { id("keyguard.cargo-common") }
            val inherited = tasks.register<CargoBuildTask>("inherited")
            val overridden = tasks.register<CargoBuildTask>("overridden") { offline.set(false) }
            tasks.register("verifyOffline") {
                doLast {
                    check(inherited.get().offline.get())
                    check(!overridden.get().offline.get())
                }
            }
            """.trimIndent(),
        )
        fixtureGradleRunner(root, "verifyOffline", "-Pkeyguard.nativeCargo.cargoOffline=true").build()
    }
}
