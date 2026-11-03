package com.artemchep.keyguard.buildplugins.resources

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateLocaleConfigTask : DefaultTask() {
    @get:Input
    abstract val localeDirectoryNames: ListProperty<String>

    @get:Input
    abstract val defaultLocale: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:OutputDirectory
    abstract val kotlinOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val resOutputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val locales = linkedSetOf(defaultLocale.get())
        localeDirectoryNames.get()
            .sorted()
            .forEach { name ->
                parseLocaleDirectory(name)?.let { locales += it.toLanguageTag() }
            }

        val localeList = locales.toList()
        writeKotlin(localeList)
        writeResources(localeList)
    }

    private fun LocaleDirectory.toLanguageTag(): String =
        listOfNotNull(language, script, region)
            .plus(variants)
            .joinToString(separator = "-")

    private fun writeKotlin(locales: List<String>) {
        val payload = locales.joinToString { "\"$it\"" }
        val content = """
package ${packageName.get()}

data object LocaleConfig {
    val locales = listOf($payload)
}
        """.trimIndent()

        kotlinOutputDir.get().asFile.resolve("LocaleConfig.kt").apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    private fun writeResources(locales: List<String>) {
        val payload = locales.joinToString(separator = "\n") {
            "<locale android:name=\"$it\" />"
        }
        val content = """
<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
$payload
</locale-config>
        """.trimIndent()

        resOutputDir.get().asFile.resolve("xml/locale_config.xml").apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }
}
