package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CargoCommonPluginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `registers task identities before configuration and tracks later property changes`() {
        val project = createProject()
        val extension = project.extensions.getByType<CargoCommonExtension>()
        val registered = extension.register("compileFixture", "cargoBuildFixture")
        assertTrue(project.tasks.names.containsAll(listOf("compileFixture", "cargoBuildFixture")))

        // Realizing a task must not freeze extension values or the producer's build directory.
        val cargo = registered.build.get()
        extension.rustTarget.set("fixture-target")
        extension.cargoBinaryName.set("fixture.bin")
        extension.composeResourceDir.set("fixture-resources")
        project.layout.buildDirectory.set(project.layout.projectDirectory.dir("relocated-output"))

        val expectedBinary = File(project.projectDir, "relocated-output/cargo-target/fixture-target/release/fixture.bin")
        assertEquals(expectedBinary, cargo.outputBinary.get().asFile)
        val compile = registered.compile.get()
        assertEquals(
            File(project.projectDir, "relocated-output/bundled-app-resources/fixture-resources/fixture.bin"),
            compile.destinationBinary.get().asFile,
        )
        assertTrue(compile.taskDependencies.getDependencies(compile).contains(cargo))

        val nativeArtifact = project.configurations
            .getByName(CargoCommonPlugin.NATIVE_DESKTOP_LIBRARY_ELEMENTS_CONFIGURATION_NAME)
            .artifacts.single()
        assertEquals(expectedBinary, nativeArtifact.file)
        assertTrue(nativeArtifact.buildDependencies.getDependencies(null).contains(compile))

        val bundledArtifact = project.configurations
            .getByName(CargoCommonPlugin.BUNDLED_APP_RESOURCES_ELEMENTS_CONFIGURATION_NAME)
            .artifacts.single()
        assertEquals(File(project.projectDir, "relocated-output/bundled-app-resources"), bundledArtifact.file)
        assertTrue(bundledArtifact.buildDependencies.getDependencies(null).contains(compile))
    }

    @Test
    fun `tracks added source roots while excluding cargo output trees`() {
        val project = createProject()
        val extension = project.extensions.getByType<CargoCommonExtension>()
        extension.cargoBinaryName.set("fixture.bin")
        val cargo = extension.register("compileFixture").build.get()

        val source = writeFile(project.projectDir, "src/lib.rs")
        val nestedSource = writeFile(project.projectDir, "src/crates/core/lib.rs")
        writeFile(project.projectDir, "src/target/debug/generated.rs")
        writeFile(project.projectDir, "src/crates/core/target/debug/generated.rs")
        val schema = writeFile(project.projectDir, "schema/api.proto")
        writeFile(project.projectDir, "schema/target/generated.rs")
        extension.extraSourceInputs.from(File(project.projectDir, "schema"))

        assertEquals(setOf(source, nestedSource, schema), cargo.sourceFiles.files)
    }

    private fun createProject(): Project = ProjectBuilder.builder()
        .withProjectDir(temporaryFolder.newFolder())
        .build()
        .also { project -> project.pluginManager.apply(CargoCommonPlugin::class.java) }

    private fun writeFile(root: File, path: String): File = File(root, path).apply {
        parentFile.mkdirs()
        writeText(path)
    }
}
