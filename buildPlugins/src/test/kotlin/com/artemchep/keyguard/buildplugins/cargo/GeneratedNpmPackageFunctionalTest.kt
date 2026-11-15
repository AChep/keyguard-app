package com.artemchep.keyguard.buildplugins.cargo

import com.artemchep.keyguard.buildplugins.fixtureGradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneratedNpmPackageFunctionalTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun refreshesSharedInstallationBeforeAnotherProjectConsumesIt() {
        val root = temporaryFolder.newFolder()
        root.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "sample"
            include(":library", ":application")
            """.trimIndent(),
        )
        root.resolve("build.gradle.kts").writeText(
            """
            plugins { id("keyguard.rust-wasm-library") }
            // Model Yarn restoring its cached file dependency at a fixed version.
            val cached = layout.projectDirectory.dir("cached-package")
            val installed = layout.buildDirectory.dir("js/node_modules/sample")
            tasks.register<Sync>("kotlinNpmInstall") {
                from(cached)
                into(installed)
            }
            """.trimIndent(),
        )
        val library = root.resolve("library").apply { mkdirs() }
        library.resolve("build.gradle.kts").writeText(
            """
            import com.artemchep.keyguard.buildplugins.cargo.refreshGeneratedNpmPackage
            plugins { id("keyguard.rust-wasm-library") apply false }
            val staged = layout.buildDirectory.dir("npm")
            val stage = tasks.register<Sync>("stagePackage") { from("package"); into(staged) }
            refreshGeneratedNpmPackage(
                "kotlinNpmInstall", "sample", stage, staged,
                rootProject.layout.buildDirectory.dir("js/node_modules"),
            )
            """.trimIndent(),
        )
        root.resolve("application/build.gradle.kts").apply {
            parentFile.mkdirs()
            writeText(
                """
                import org.gradle.api.file.DirectoryProperty
                import org.gradle.api.file.FileSystemOperations
                import org.jetbrains.kotlin.gradle.targets.js.RequiredKotlinJsDependency
                import org.jetbrains.kotlin.gradle.targets.js.npm.RequiresNpmDependenciesTask
                import javax.inject.Inject
                plugins { id("keyguard.rust-wasm-library") }
                @OptIn(org.jetbrains.kotlin.gradle.InternalKotlinGradlePluginApi::class)
                fun Bundle.useGeneratedPackage(directory: Provider<Directory>) {
                    fileBasedNpmDependencyLocations.add(directory.map { it.asFile.absolutePath })
                }
                abstract class Bundle : DefaultTask(), RequiresNpmDependenciesTask {
                    @get:Internal
                    override val compilation: org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrCompilation
                        get() = error("This fixture does not compile Kotlin")
                    @get:Internal abstract val installedPackage: DirectoryProperty
                    @get:OutputDirectory abstract val bundleDirectory: DirectoryProperty
                    @get:Inject abstract val fs: FileSystemOperations
                    override val requiredNpmDependencies: Set<RequiredKotlinJsDependency> = emptySet()
                    @TaskAction fun bundle() {
                        fs.sync { from(installedPackage); into(bundleDirectory) }
                    }
                }
                tasks.register<Bundle>("bundle") {
                    dependsOn(rootProject.tasks.named("kotlinNpmInstall"))
                    useGeneratedPackage(project(":library").layout.buildDirectory.dir("npm"))
                    installedPackage.set(rootProject.layout.buildDirectory.dir("js/node_modules/sample"))
                    bundleDirectory.set(layout.buildDirectory.dir("bundle"))
                }
                """.trimIndent(),
            )
        }
        val generated = library.resolve("package").apply { mkdirs() }
        val installed = root.resolve("build/js/node_modules/sample")
        val bundled = root.resolve("application/build/bundle")
        generated.resolve("package.json").writeText("""{"name":"sample","version":"0.1.0"}""")
        generated.resolve("runtime.wasm").writeText("binary-v1")
        generated.resolve("index.mjs").writeText("adapter-v1")
        generated.resolve("obsolete.mjs").writeText("obsolete")
        root.resolve("cached-package").apply { generated.copyRecursively(this) }
        // This compiler-free task stub cannot be restored by Gradle's Kotlin DSL
        // classloader codec. Real JS/WasmGC tasks cover configuration-cache reuse.
        fun run() = fixtureGradleRunner(root, ":application:bundle", "--no-configuration-cache").build()

        run()
        assertEquals("binary-v1", bundled.resolve("runtime.wasm").readText())
        val unchanged = run()
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":kotlinNpmInstall")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":application:bundle")?.outcome)

        generated.resolve("runtime.wasm").writeText("binary-v2")
        generated.resolve("index.mjs").writeText("adapter-v2")
        generated.resolve("obsolete.mjs").delete()
        val changed = run()
        assertEquals(TaskOutcome.SUCCESS, changed.task(":kotlinNpmInstall")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, changed.task(":application:bundle")?.outcome)
        assertEquals("binary-v2", bundled.resolve("runtime.wasm").readText())
        assertEquals("adapter-v2", bundled.resolve("index.mjs").readText())
        assertTrue(!bundled.resolve("obsolete.mjs").exists())

        installed.resolve("runtime.wasm").delete()
        assertEquals(TaskOutcome.SUCCESS, run().task(":kotlinNpmInstall")?.outcome)
        assertEquals("binary-v2", installed.resolve("runtime.wasm").readText())
    }
}
