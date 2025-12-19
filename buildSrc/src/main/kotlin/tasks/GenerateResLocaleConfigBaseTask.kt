package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import java.io.File

abstract class GenerateResLocaleConfigBaseTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val composeResourcesDir: DirectoryProperty

    protected fun collectLocalesFromComposeResources(dir: File): List<String> {
        val locales = mutableListOf<String>()
        locales += "en-US" // the default locale!

        // Find all the locales that the
        // app currently supports.
        val regex = "^values-(([a-z]{2})(-r([A-Z]+))?)$".toRegex()
        //val root = file("src/commonMain/composeResources")
        dir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val dirName = dir.name

            val match = regex.matchEntire(dirName)
                ?: return@forEach
            val language = match.groupValues.getOrNull(2)
                ?: return@forEach
            val region = match.groupValues.getOrNull(4)
            locales += language + region?.let { "-$it" }
        }
        return locales
    }
}

