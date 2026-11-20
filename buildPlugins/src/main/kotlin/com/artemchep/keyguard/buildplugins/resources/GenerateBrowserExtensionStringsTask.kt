package com.artemchep.keyguard.buildplugins.resources

import groovy.json.JsonGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Generates the `_locales/<locale>/messages.json` files of the browser extension
 * and a TypeScript union of their keys, from the app's string resources. Only
 * the keys of the allowlist are exported, so the extension ships the strings it
 * uses and Crowdin stays the single source of translations.
 *
 * A plural `key` becomes one `key__<category>` message per CLDR category and a
 * `key__locale` message: the BCP 47 locale whose plural rules pick the category,
 * which is English in a locale that doesn't translate the plural. The extension
 * picks one with `Intl.PluralRules`.
 */
abstract class GenerateBrowserExtensionStringsTask : DefaultTask() {
    @get:Internal
    abstract val composeResourcesDir: DirectoryProperty

    /** Only the string resources, so drawable/font/file changes keep the task up to date. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val stringResources: FileTree
        get() = composeResourcesDir.asFileTree.matching {
            include("values*/strings.xml", "values*/plurals.xml")
        }

    /** One key per line; blank lines and lines starting with `#` are skipped. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allowlistFile: RegularFileProperty

    /** The extension's `_locales` directory. The task owns all of it. */
    @get:OutputDirectory
    abstract val localesDir: DirectoryProperty

    @get:OutputFile
    abstract val keysFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val allowlist = readAllowlist(allowlistFile.get().asFile)
        val resourcesDir = composeResourcesDir.get().asFile
        // Translations follow the base file: a key only they have is stale. The
        // base directory alone, as a regional one (`values-en-rCA`) may map to `en` too.
        val base = readStringResources(resourcesDir) { name -> BASE_STRINGS_LOCALE.takeIf { name == "values" } }
        val baseStrings = base.strings.keys
        val basePlurals = base.plurals.keys
        val unknown = allowlist.filter { it !in baseStrings && it !in basePlurals }
        if (unknown.isNotEmpty()) {
            throw GradleException(
                "strings.allowlist names keys that are not in the base strings.xml or plurals.xml: " +
                    unknown.joinToString(),
            )
        }
        val ambiguous = allowlist.filter { it in baseStrings && it in basePlurals }
        if (ambiguous.isNotEmpty()) {
            throw GradleException("Keys are both a string and a plural: ${ambiguous.joinToString()}")
        }
        val stringKeys = allowlist.filter { it in baseStrings }.toSortedSet()
        val pluralKeys = allowlist.filter { it in basePlurals }.toSortedSet()
        val withoutOther = pluralKeys.filter { key ->
            "other" !in base.plurals.getValue(key).getValue(BASE_STRINGS_LOCALE)
        }
        if (withoutOther.isNotEmpty()) {
            throw GradleException("Plurals without `other` in the base plurals.xml: ${withoutOther.joinToString()}")
        }
        val clashes = stringKeys.filter { key -> pluralKeys.any { plural -> key in pluralMessageKeys(plural) } }
        if (clashes.isNotEmpty()) {
            throw GradleException("Keys clash with plural messages: ${clashes.joinToString()}")
        }
        checkLocaleCollisions(resourcesDir)
        val resources = readStringResources(resourcesDir) { chromeLocale(it) }

        val strings = withBaseFallback(resources.strings.filterKeys { it in stringKeys }, resources.locales)

        val files = resources.locales.associate { locale ->
            val messages = sortedMapOf<String, Any>()
            strings.forEach { (key, translations) ->
                translations[locale]?.let { messages[key] = chromeMessage(key, it) }
            }
            pluralKeys.forEach { key ->
                val translations = resources.plurals.getValue(key)
                // A plural is the locale's own or English as a whole, and its
                // category follows the plural rules of that language.
                val pluralLocale = locale.takeIf { "other" in translations[it].orEmpty() } ?: BASE_STRINGS_LOCALE
                val variants = translations.getValue(pluralLocale)
                // Fill every category from the locale's own `other`, so the browser
                // never falls back to an English category inside a translated plural.
                val other = variants.getValue("other")
                PLURAL_CATEGORIES.forEach { category ->
                    val messageKey = pluralMessageKey(key, category)
                    messages[messageKey] = chromeMessage(key, variants[category] ?: other)
                }
                messages[pluralLocaleMessageKey(key)] = mapOf("message" to pluralLocale.replace('_', '-'))
            }
            "$locale/messages.json" to buildString {
                appendJson(messages)
                append('\n')
            }
        }
        writeLocales(files)
        writeKeys(stringKeys, pluralKeys)

        logger.lifecycle(
            "Generated browser extension strings: ${stringKeys.size} strings + ${pluralKeys.size} plurals " +
                "across ${files.size} locales.",
        )
    }

    /**
     * Fails when two resource directories map to one browser locale, as
     * `values-es-rES` and `values-es-rMX` both would to `es`: their translations
     * would merge key by key.
     */
    private fun checkLocaleCollisions(resourcesDir: File) {
        val collisions = localeDirectoryNames(resourcesDir)
            .filter { name -> STRING_FILES.any { resourcesDir.resolve(name).resolve(it).isFile } }
            .groupBy { chromeLocale(it) }
            .filter { (locale, names) -> locale != null && names.size > 1 }
        if (collisions.isNotEmpty()) {
            throw GradleException(
                "Resource directories map to the same browser locale, " +
                    collisions.entries.joinToString("; ") { (locale, names) -> "$locale: ${names.joinToString()}" } +
                    ". Map or skip one in GenerateBrowserExtensionStringsTask.chromeLocale().",
            )
        }
    }

    private fun readAllowlist(file: File): List<String> {
        val keys = file.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()
        val invalid = keys.filterNot(KEY::matches)
        if (invalid.isNotEmpty()) {
            throw GradleException("strings.allowlist has invalid keys: ${invalid.joinToString()}")
        }
        return keys
    }

    // region messages.json

    /**
     * Converts an Android string to a Chrome message: `%1$s` and `%d` become
     * `$argN$` placeholders of the substitution `$N`, `%%` becomes `%`, and a
     * literal `$` becomes `$$`.
     */
    private fun chromeMessage(key: String, value: String): Map<String, Any> {
        val indices = sortedSetOf<Int>()
        var next = 1
        val message = MESSAGE_TOKEN.replace(value) { match ->
            val (position, type) = match.destructured
            when {
                match.value == "$" -> "\$\$"
                type == "%" -> "%"
                else -> {
                    val index = position.toIntOrNull() ?: next++
                    if (index !in 1..MAX_SUBSTITUTIONS) {
                        throw GradleException("$key: Chrome supports up to $MAX_SUBSTITUTIONS arguments.")
                    }
                    indices += index
                    "\$arg$index\$"
                }
            }
        }
        if (indices.isEmpty()) return mapOf("message" to message)
        return mapOf(
            "message" to message,
            "placeholders" to indices.associate { index -> "arg$index" to mapOf("content" to "\$$index") },
        )
    }

    /** Writes [files] (relative path -> content), and removes any other file. */
    private fun writeLocales(files: Map<String, String>) {
        val root = localesDir.get().asFile
        root.walkBottomUp().forEach { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            when {
                file == root -> Unit
                file.isFile && path !in files -> file.delete()
                file.isDirectory && file.list().isNullOrEmpty() -> file.delete()
            }
        }
        files.forEach { (path, text) -> root.resolve(path).writeIfChanged(text) }
    }

    // endregion

    private fun writeKeys(stringKeys: Set<String>, pluralKeys: Set<String>) {
        fun union(keys: Set<String>) =
            if (keys.isEmpty()) " never;\n" else keys.joinToString("\n", "\n", ";\n") { "  | \"$it\"" }
        val text = buildString {
            append("// Generated by ./gradlew :common:generateBrowserExtensionStrings from\n")
            append("// strings.allowlist. Do not edit.\n\n")
            append("/** A message of the extension's `_locales`. */\n")
            append("export type I18nKey =").append(union(stringKeys)).append('\n')
            append("/**\n")
            append(" * A plural of the extension's `_locales`: one `<key>__<category>` message per\n")
            append(" * category, and `<key>__locale`, the locale of the plural rules that pick it.\n")
            append(" */\n")
            append("export type I18nPluralKey =").append(union(pluralKeys))
        }
        keysFile.get().asFile.writeIfChanged(text)
    }

    /** Skips identical content, so the extension's watcher doesn't rebuild. */
    private fun File.writeIfChanged(text: String) {
        if (isFile && readText() == text) return
        parentFile.mkdirs()
        writeText(text)
    }

    /** Pretty-prints maps of strings, two spaces per level. */
    private fun StringBuilder.appendJson(value: Any, indent: String = "") {
        if (value !is Map<*, *>) {
            append(JSON.toJson(value))
            return
        }
        append("{\n")
        value.entries.forEachIndexed { index, (key, child) ->
            if (index > 0) append(",\n")
            append(indent).append("  ").append(JSON.toJson(key)).append(": ")
            appendJson(child!!, "$indent  ")
        }
        append('\n').append(indent).append('}')
    }

    companion object {
        /** CLDR plural categories, as `Intl.PluralRules.select()` returns them. */
        internal val PLURAL_CATEGORIES = listOf("zero", "one", "two", "few", "many", "other")

        private val STRING_FILES = listOf("strings.xml", "plurals.xml")

        /** `chrome.i18n.getMessage()` takes at most 9 substitutions. */
        private const val MAX_SUBSTITUTIONS = 9

        private val KEY = Regex("[A-Za-z0-9_]+")

        // A format specifier (as ANDROID_FORMAT_SPECIFIER) or a literal dollar sign.
        private val MESSAGE_TOKEN = Regex("""%(?:(\d+)\$)?([sdf%])|\$""")

        private val JSON = JsonGenerator.Options().disableUnicodeEscaping().build()

        /**
         * The locales of `chrome.i18n`, from
         * https://developer.chrome.com/docs/extensions/reference/api/i18n#locales.
         * Firefox accepts the same codes.
         */
        private val CHROME_LOCALES = setOf(
            "ar", "am", "bg", "bn", "ca", "cs", "da", "de", "el", "en", "en_AU", "en_GB", "en_US",
            "es", "es_419", "et", "fa", "fi", "fil", "fr", "gu", "he", "hi", "hr", "hu", "id", "it",
            "ja", "kn", "ko", "lt", "lv", "ml", "mr", "ms", "nl", "no", "pl", "pt_BR", "pt_PT", "ro",
            "ru", "sk", "sl", "sr", "sv", "sw", "ta", "te", "th", "tr", "uk", "vi", "zh_CN", "zh_TW",
        )

        internal fun pluralMessageKey(key: String, category: String) = "${key}__$category"

        internal fun pluralLocaleMessageKey(key: String) = "${key}__locale"

        /** Every message of the plural [key]. */
        private fun pluralMessageKeys(key: String) =
            PLURAL_CATEGORIES.map { pluralMessageKey(key, it) } + pluralLocaleMessageKey(key)

        /**
         * Maps a resource directory to a Chrome locale: `language_REGION` when Chrome
         * has it, else the bare language, else `null` to drop the directory.
         */
        internal fun chromeLocale(dirName: String): String? {
            if (dirName == "values") return BASE_STRINGS_LOCALE
            val directory = parseLocaleDirectory(dirName) ?: return null
            val language = when (directory.language) {
                "iw" -> "he" // legacy Hebrew code
                "in" -> "id" // legacy Indonesian code
                "nb" -> "no" // Chrome names Norwegian Bokmål `no`
                "tl" -> "fil"
                else -> directory.language
            }
            val region = directory.region ?: when (directory.script.takeIf { language == "zh" }) {
                "Hans" -> "CN"
                "Hant" -> "TW"
                else -> null
            }
            val regional = region?.let { "${language}_$it" }
            return when {
                regional != null && regional in CHROME_LOCALES -> regional
                language in CHROME_LOCALES -> language
                else -> null
            }
        }
    }
}
