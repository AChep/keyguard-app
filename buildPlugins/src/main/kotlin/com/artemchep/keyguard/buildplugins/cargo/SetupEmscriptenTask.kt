package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RelativePath
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import java.io.File
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import javax.inject.Inject

/** Validates the shared SDK on every invocation, provisioning it only when absent. */
@UntrackedTask(because = "emsdk owns its mutable installation and compiler cache")
abstract class SetupEmscriptenTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Inject abstract val fileOperations: FileSystemOperations
    @get:Inject abstract val archiveOperations: ArchiveOperations

    @get:Internal abstract val sdkDirectory: DirectoryProperty
    @get:Input abstract val version: Property<String>
    @get:Input abstract val installerRevision: Property<String>
    @get:Input abstract val installerChecksum: Property<String>
    @get:Input abstract val pythonExecutable: Property<String>
    @get:Input abstract val managed: Property<Boolean>
    @get:Input abstract val offline: Property<Boolean>

    @TaskAction
    fun setup() {
        val sdk = sdkDirectory.get().asFile
        if (!managed.get()) {
            verify(sdk)
            return
        }
        sdk.parentFile.mkdirs()
        // Several independent Gradle builds can share this installation.
        FileChannel.open(sdk.resolveSibling("${sdk.name}.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            .use { channel ->
                channel.lock().use {
                    val marker = sdk.resolve(".keyguard-install")
                    val identity = "${version.get()} ${installerRevision.get()} ${installerChecksum.get()}"
                    if (marker.isFile && marker.readText() == identity && installed(sdk)) {
                        verify(sdk)
                        return
                    }
                    check(!offline.get()) {
                        "Emscripten ${version.get()} is not installed at $sdk. Run once online or set keyguard.wasm.emsdkDir."
                    }
                    logger.lifecycle("Installing Emscripten ${version.get()} into $sdk")
                    val archive = sdk.resolveSibling("${sdk.name}-source.zip")
                    downloadInstaller(archive)
                    fileOperations.copy {
                        from(archiveOperations.zipTree(archive))
                        into(sdk)
                        includeEmptyDirs = false
                        eachFile {
                            relativePath = RelativePath(!isDirectory, *relativePath.segments.drop(1).toTypedArray())
                        }
                    }
                    for (operation in listOf("install", "activate")) {
                        execOperations.exec {
                            workingDir(sdk)
                            commandLine(pythonExecutable.get(), sdk.resolve("emsdk.py").absolutePath, operation, version.get())
                            environment("EMSDK_QUIET", "1")
                        }
                    }
                    verify(sdk)
                    marker.writeText(identity)
                }
            }
    }

    private fun downloadInstaller(archive: File) {
        if (archive.isFile && checksum(archive) == installerChecksum.get()) return
        val connection = URI("https://codeload.github.com/emscripten-core/emsdk/zip/${installerRevision.get()}")
            .toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 120_000
            }
        val partial = archive.resolveSibling("${archive.name}.part")
        try {
            connection.getInputStream().use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
            check(checksum(partial) == installerChecksum.get()) { "Emsdk installer checksum mismatch" }
            java.nio.file.Files.move(partial.toPath(), archive.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            partial.delete()
        }
    }

    private fun installed(sdk: File): Boolean = listOf(
        ".emscripten", "upstream/emscripten/emcc.py", "upstream/emscripten/emscripten-version.txt",
    ).all { sdk.resolve(it).isFile }

    private fun verify(sdk: File) {
        check(installed(sdk)) { "Expected an activated Emscripten ${version.get()} SDK at $sdk" }
        val actual = sdk.resolve("upstream/emscripten/emscripten-version.txt").readText().trim().trim('"')
        check(actual == version.get()) { "Expected Emscripten ${version.get()}, found $actual at $sdk" }
    }

    private fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                digest.update(buffer, 0, length)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
