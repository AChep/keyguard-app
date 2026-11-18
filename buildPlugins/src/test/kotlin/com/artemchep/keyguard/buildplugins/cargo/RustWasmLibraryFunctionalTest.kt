package com.artemchep.keyguard.buildplugins.cargo

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RustWasmLibraryFunctionalTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun registrationDoesNotProvisionTheSdk() {
        val root = temporaryFolder.newFolder()
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"sample\"")
        root.resolve("build.gradle.kts").writeText(
            """
            plugins { id("keyguard.rust-wasm-library") }
            keyguardRustWasm { sdkDirectory.set(layout.projectDirectory.dir("unused-sdk")) }
            """.trimIndent(),
        )
        val result = fixtureGradleRunner(root, "help", "--configuration-cache").build()
        assertEquals(null, result.task(":setupNativeSampleWasmSdk"))
        assertFalse(root.resolve("unused-sdk").exists())
    }

    @Test
    fun tracksArchiveLinkFlagsAndPackageChangesWithoutUnnecessaryLinking() {
        val root = fixture()
        fun run(vararg args: String) = fixtureGradleRunner(
            root, "buildNativeSampleWasm", "clippyNativeSampleWasm", "--configuration-cache",
            "-Pkeyguard.wasm.emsdkDir=sdk", *args,
        ).build()

        assertEquals(TaskOutcome.SUCCESS, run().task(":linkNativeSampleWasm")?.outcome)
        assertTrue(root.resolve("build/wasm/npm/keyguard_sample.wasm").isFile)
        assertEquals("adapter-v1", root.resolve("build/wasm/npm/index.mjs").readText())

        val unchanged = run()
        assertTrue(unchanged.output.contains("Reusing configuration cache"))
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":linkNativeSampleWasm")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":buildNativeSampleWasm")?.outcome)

        root.resolve("archive-source").writeText("archive-v2")
        assertEquals(TaskOutcome.SUCCESS, run().task(":linkNativeSampleWasm")?.outcome)
        assertTrue(root.resolve("build/wasm/npm/keyguard_sample.wasm").readText().contains("archive-v2"))

        root.resolve("index.mjs").writeText("adapter-v2")
        val adapter = run()
        assertEquals(TaskOutcome.UP_TO_DATE, adapter.task(":linkNativeSampleWasm")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, adapter.task(":buildNativeSampleWasm")?.outcome)
        assertEquals("adapter-v2", root.resolve("build/wasm/npm/index.mjs").readText())

        assertEquals(TaskOutcome.SUCCESS, run("-PextraLinkFlag=-g").task(":linkNativeSampleWasm")?.outcome)
    }

    @Test
    fun aVariantHasItsOwnCargoArgumentsExportsAndDirectories() {
        val root = fixture()
        root.resolve("build.gradle.kts").appendText(
            "\n" + """
            keyguardRustWasm {
                variants.register("noise") {
                    cargoArguments.add("--no-default-features")
                    exportedFunctions.set(listOf("malloc"))
                }
            }
            val produceNoiseArchive = tasks.register<Copy>("produceNoiseArchive") {
                from("archive-source")
                into(layout.buildDirectory.dir("wasm/cargo-noise/wasm32-unknown-emscripten/release"))
                rename { "libkeyguard_sample_c.a" }
            }
            val noiseCargo = tasks.named<CargoBuildTask>("cargoBuildNativeSampleWasmNoise")
            noiseCargo.configure {
                enabled = false
                dependsOn(produceNoiseArchive)
            }
            val noiseClippy = tasks.named<EmscriptenClippyTask>("clippyNativeSampleWasmNoise")
            tasks.register("printNoiseArguments") {
                val cargo = noiseCargo.flatMap { it.cargoArguments }
                val clippy = noiseClippy.flatMap { it.cargoArguments }
                doLast { println("cargo=" + cargo.get() + " clippy=" + clippy.get()) }
            }
            """.trimIndent(),
        )
        val result = fixtureGradleRunner(
            root, "buildNativeSampleWasm", "buildNativeSampleWasmNoise", "printNoiseArguments",
            "--configuration-cache", "-Pkeyguard.wasm.emsdkDir=sdk",
        ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":linkNativeSampleWasmNoise")?.outcome)
        assertTrue(result.output.contains("cargo=[--locked, --jobs, 4, --no-default-features] clippy=[--no-default-features]"))
        val noise = root.resolve("build/wasm/npm-noise/keyguard_sample.wasm").readText()
        assertTrue(noise.contains("-sEXPORTED_FUNCTIONS=[\"_malloc\"]"))
        assertEquals("adapter-v1", root.resolve("build/wasm/npm-noise/index.mjs").readText())
        // The default build keeps its exports and directories.
        val default = root.resolve("build/wasm/npm/keyguard_sample.wasm").readText()
        assertTrue(default.contains("-sEXPORTED_FUNCTIONS=[\"_malloc\",\"_free\"]"))
    }

    @Test
    fun rejectsAnOverrideWithTheWrongSdkVersion() {
        val root = fixture()
        root.resolve("sdk/upstream/emscripten/emscripten-version.txt").writeText("99.0.0")
        val result = fixtureGradleRunner(
            root, "buildNativeSampleWasm", "-Pkeyguard.wasm.emsdkDir=sdk",
        ).buildAndFail()
        assertTrue(result.output.contains("Expected Emscripten 4.0.23, found 99.0.0"))
        assertEquals(null, result.task(":linkNativeSampleWasm"))
    }

    private fun fixture(): File {
        val root = temporaryFolder.newFolder()
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"sample\"")
        root.resolve("index.mjs").writeText("adapter-v1")
        root.resolve("package.json").writeText("""{"name":"sample","version":"0.1.0"}""")
        root.resolve("archive-source").writeText("archive-v1")
        root.resolve("sdk/.emscripten").apply { parentFile.mkdirs(); writeText("fixture") }
        root.resolve("sdk/upstream/emscripten/emscripten-version.txt").apply {
            parentFile.mkdirs()
            writeText("4.0.23")
        }
        root.resolve("sdk/upstream/emscripten/emcc.py").writeText(
            """
            import os, pathlib, sys
            sdk = pathlib.Path(__file__).resolve().parents[2]
            assert os.environ['EM_CONFIG'] == str(sdk / '.emscripten')
            assert os.environ['EM_CACHE'] == str(sdk / 'upstream/emscripten/cache')
            output = pathlib.Path(sys.argv[sys.argv.index('-o') + 1])
            archive = pathlib.Path(sys.argv[1]).read_bytes()
            output.write_text('compiled')
            output.with_suffix('.wasm').write_bytes(archive + str(sys.argv[2:]).encode())
            """.trimIndent(),
        )
        root.resolve("build.gradle.kts").writeText(
            """
            import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask
            import com.artemchep.keyguard.buildplugins.cargo.EmscriptenClippyTask
            plugins { id("keyguard.rust-wasm-library") }
            keyguardRustWasm {
                exportedFunctions.set(listOf("malloc", "free"))
                packageFiles.from("index.mjs", "package.json")
                linkerArguments.set(providers.gradleProperty("extraLinkFlag").map { listOf(it) }.orElse(emptyList()))
            }
            val produceArchive = tasks.register<Copy>("produceArchive") {
                from("archive-source")
                into(layout.buildDirectory.dir("wasm/cargo/wasm32-unknown-emscripten/release"))
                rename { "libkeyguard_sample_c.a" }
            }
            tasks.named<CargoBuildTask>("cargoBuildNativeSampleWasm") {
                enabled = false
                dependsOn(produceArchive)
            }
            tasks.named("verifyNativeSampleWasmRustTarget") { enabled = false }
            tasks.named<EmscriptenClippyTask>("clippyNativeSampleWasm") {
                actions.clear()
                doLast { logger.lifecycle("Fixture Clippy shares Cargo's cache") }
            }
            """.trimIndent(),
        )
        return root
    }
}
