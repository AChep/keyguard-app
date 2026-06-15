package com.artemchep.keyguard.buildplugins.resources

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.util.Locale

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
                parseLocaleDirectoryName(name)?.let(locales::add)
            }

        val localeList = locales.toList()
        writeKotlin(localeList)
        writeResources(localeList)
    }

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

    private fun parseLocaleDirectoryName(name: String): String? {
        if (name == "values") {
            return null
        }
        if (!name.startsWith("values-")) {
            return null
        }

        val qualifier = name.removePrefix("values-")
        if (qualifier.startsWith("b+")) {
            return parseBcp47(name, qualifier.removePrefix("b+"))
        }

        val parts = qualifier.split('-')
        val language = parts.firstOrNull()
            ?: return null
        if (!language.matches(Regex("[a-z]{2,8}"))) {
            return null
        }

        var script: String? = null
        var region: String? = null
        parts.drop(1).forEach { part ->
            when {
                script == null && part.length == 4 && part.all(Char::isLetter) -> {
                    script = part.lowercase().replaceFirstChar(Char::titlecase)
                }

                region == null && part.startsWith("r") && part.length > 1 -> {
                    region = canonicalizeRegion(part.removePrefix("r"))
                }

                else -> throw GradleException("Unsupported locale qualifier directory: $name")
            }
        }

        return buildList {
            add(language.lowercase())
            script?.let(::add)
            region?.let(::add)
        }.joinToString(separator = "-")
    }

    private fun parseBcp47(directoryName: String, rawTag: String): String {
        val segments = rawTag.split('+')
            .filter(String::isNotBlank)
        if (segments.isEmpty()) {
            throw GradleException("Unsupported locale qualifier directory: $directoryName")
        }

        return segments.mapIndexed { index, segment ->
            when {
                index == 0 -> segment.lowercase()
                segment.length == 4 && segment.all(Char::isLetter) ->
                    segment.lowercase().replaceFirstChar(Char::titlecase)

                segment.length == 2 && segment.all(Char::isLetter) -> segment.uppercase(Locale.ROOT)
                segment.length == 3 && segment.all(Char::isDigit) -> segment
                else -> segment
            }
        }.joinToString(separator = "-")
    }

    private fun canonicalizeRegion(region: String): String =
        when {
            region.length == 2 && region.all(Char::isLetter) -> region.uppercase(Locale.ROOT)
            region.length == 3 && region.all(Char::isDigit) -> region
            else -> throw GradleException("Unsupported locale region qualifier: $region")
        }
}
