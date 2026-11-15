package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Uses a local SDK; declared inputs support incremental linking")
abstract class EmscriptenLinkTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val archive: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.ABSOLUTE) abstract val compiler: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.ABSOLUTE) abstract val sdkConfiguration: RegularFileProperty
    @get:Internal abstract val environmentVariables: MapProperty<String, String>
    @get:Input abstract val sdkVersion: Property<String>
    @get:Input abstract val pythonExecutable: Property<String>
    @get:Input abstract val outputName: Property<String>
    @get:Input abstract val factoryName: Property<String>
    @get:Input abstract val exportedFunctions: ListProperty<String>
    @get:Input abstract val linkerArguments: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    internal fun commandLine(): List<String> {
        val exports = exportedFunctions.get()
        require(exports.all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) }) { "Invalid C export name" }
        return listOf(
            pythonExecutable.get(), compiler.get().asFile.absolutePath, archive.get().asFile.absolutePath,
            "--no-entry", "-O3", "-sMODULARIZE=1", "-sEXPORT_ES6=1", "-sEXPORT_NAME=${factoryName.get()}",
            "-sENVIRONMENT=web,worker,node", "-sALLOW_MEMORY_GROWTH=1", "-sMAXIMUM_MEMORY=2147483648",
            "-sSTACK_SIZE=2097152", "-sDYNAMIC_EXECUTION=0", "-sWASM_BIGINT=1", "-fwasm-exceptions",
            "-sEXPORTED_RUNTIME_METHODS=[\"HEAPU8\",\"HEAPU32\",\"HEAP32\"]",
            "-sEXPORTED_FUNCTIONS=" + exports.joinToString(",", "[", "]") { "\"_$it\"" },
        ) + linkerArguments.get() + listOf("-o", outputDirectory.file("${outputName.get()}.mjs").get().asFile.absolutePath)
    }

    @TaskAction
    fun link() {
        outputDirectory.get().asFile.mkdirs()
        execOperations.exec {
            environment(environmentVariables.get())
            commandLine(this@EmscriptenLinkTask.commandLine())
        }
    }
}
