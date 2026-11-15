package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Stages local compiler output, including source maps")
abstract class StageWasmBrowserProbeTask : DefaultTask() {
    @get:Inject abstract val fileOperations: FileSystemOperations
    @get:InputFile @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val entryFile: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val compilerOutput: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val runtimePackage: DirectoryProperty
    @get:Input abstract val moduleSpecifier: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val entryName = entryFile.get().asFile.name
        fileOperations.sync {
            from(compilerOutput)
            from(runtimePackage) { into("rust") }
            into(outputDirectory)
        }
        val output = outputDirectory.get().asFile
        val imports = output.resolve(entryName.removeSuffix(".mjs") + ".import-object.mjs")
        val source = imports.readText()
        val specifier = "from '${moduleSpecifier.get()}'"
        check(source.indexOf(specifier) >= 0 && source.indexOf(specifier) == source.lastIndexOf(specifier)) {
            "Expected one runtime import in Kotlin/Wasm output"
        }
        imports.writeText(source.replace(specifier, "from './rust/index.mjs'"))
        output.resolve("worker.mjs").writeText("import './$entryName';\n")
    }
}
