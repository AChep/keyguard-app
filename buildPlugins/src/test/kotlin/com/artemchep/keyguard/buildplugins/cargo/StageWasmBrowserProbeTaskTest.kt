package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StageWasmBrowserProbeTaskTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun stagesCompilerAndRuntimeFilesWithoutChangingTheCompilerOutput() {
        val task = fixture()
        val source = task.compilerOutput.get().asFile.resolve("probe.import-object.mjs")
        task.outputDirectory.file("stale.js").get().asFile.apply { parentFile.mkdirs(); writeText("old") }
        task.stage()
        val output = task.outputDirectory.get().asFile
        assertEquals("from './rust/index.mjs'", output.resolve("probe.import-object.mjs").readText())
        assertEquals("from 'sample'", source.readText())
        assertEquals("import './probe.mjs';\n", output.resolve("worker.mjs").readText())
        assertTrue(output.resolve("rust/runtime.wasm").isFile)
        assertFalse(output.resolve("stale.js").exists())
    }

    @Test
    fun rejectsUnexpectedCompilerImportLayouts() {
        val task = fixture()
        task.compilerOutput.file("probe.import-object.mjs").get().asFile.writeText("from 'sample'; from 'sample'")
        assertThrows(IllegalStateException::class.java) { task.stage() }
    }

    @Test
    fun quotesBindgenPathsWithoutShellExpansion() {
        val sdk = temporaryFolder.root.resolve("SDK with spaces and 'quotes'")
        val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val process = ProcessBuilder(
            python, "-c", "import shlex,sys; print(shlex.split(sys.argv[1])[0])", bindgenSysrootArgument(sdk),
        ).start()
        val decoded = process.inputStream.bufferedReader().readText().trimEnd()
        assertEquals(0, process.waitFor())
        assertEquals(
            "--sysroot=" + sdk.resolve("upstream/emscripten/cache/sysroot").invariantSeparatorsPath,
            decoded,
        )
    }

    private fun fixture(): StageWasmBrowserProbeTask {
        val root = temporaryFolder.newFolder()
        val compiler = root.resolve("compiler").apply { mkdirs() }
        compiler.resolve("probe.mjs").writeText("entry")
        compiler.resolve("probe.import-object.mjs").writeText("from 'sample'")
        val runtime = root.resolve("runtime").apply { mkdirs() }
        runtime.resolve("runtime.wasm").writeText("wasm")
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        return project.tasks.register("stage", StageWasmBrowserProbeTask::class.java).get().apply {
            entryFile.set(compiler.resolve("probe.mjs"))
            compilerOutput.set(compiler)
            runtimePackage.set(runtime)
            moduleSpecifier.set("sample")
            outputDirectory.set(root.resolve("output"))
        }
    }
}
