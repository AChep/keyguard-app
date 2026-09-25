package com.artemchep.keyguard.buildplugins.resources

import groovy.json.JsonGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

abstract class GenerateAppleStringsTask : DefaultTask() {
    @get:Internal
    abstract val composeResourcesDir: DirectoryProperty

    /** Only the string resources, so drawable/font/file changes keep the task up to date. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val stringResources: FileTree
        get() = composeResourcesDir.asFileTree.matching {
            include("values*/strings.xml", "values*/plurals.xml")
        }

    @get:OutputFile
    abstract val catalogFile: RegularFileProperty

    @get:OutputFile
    abstract val swiftFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val valueDirs = composeResourcesDir.get().asFile.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values") }
            ?.sortedBy { it.name }
            .orEmpty()

        val locales = sortedSetOf<String>()
        // key -> (appleLocale -> converted value)
        val strings = sortedMapOf<String, MutableMap<String, String>>()
        // key -> (appleLocale -> (cldrCategory -> variant value with %arg))
        val plurals = sortedMapOf<String, MutableMap<String, MutableMap<String, String>>>()
        // key -> arg types derived from the base (English) value
        val stringArgs = mutableMapOf<String, List<ArgType>>()

        for (dir in valueDirs) {
            val locale = appleLocale(dir.name) ?: continue

            dir.resolve("strings.xml").takeIf(File::isFile)?.let { file ->
                locales += locale
                parseStrings(file).forEach { (name, raw) ->
                    val value = unescape(raw)
                    strings.getOrPut(name) { sortedMapOf() }[locale] = convertValue(value)
                    if (locale == BASE_LOCALE) {
                        stringArgs[name] = extractArgTypes(value)
                    }
                }
            }

            dir.resolve("plurals.xml").takeIf(File::isFile)?.let { file ->
                locales += locale
                parsePlurals(file).forEach { (name, items) ->
                    val byCategory = plurals.getOrPut(name) { sortedMapOf() }
                        .getOrPut(locale) { linkedMapOf() }
                    items.forEach { (category, raw) ->
                        byCategory[category] = convertPluralVariant(unescape(raw))
                    }
                }
            }
        }

        // Bundle selects one localization table; absent keys in that table may
        // resolve to their identifiers rather than the development language.
        // Fill whole missing entries (including plurals) without changing Crowdin
        // sources or mixing English plural categories into translated entries.
        writeCatalog(withBaseFallback(strings, locales), withBaseFallback(plurals, locales))
        writeSwift(strings.keys, stringArgs, plurals.keys)

        logger.lifecycle(
            "Generated Apple string catalog: ${strings.size} strings + ${plurals.size} plurals " +
                "across ${locales.size} locales.",
        )
    }

    // region XML parsing

    // textContent flattens inline markup such as <xliff:g>.
    private fun parseStrings(file: File): List<Pair<String, String>> =
        parseXml(file).elementsByKey("string", "name")
            .map { (name, element) -> name to element.textContent }

    private fun parsePlurals(file: File): List<Pair<String, List<Pair<String, String>>>> =
        parseXml(file).elementsByKey("plurals", "name")
            .map { (name, element) ->
                name to element.elementsByKey("item", "quantity")
                    .map { (quantity, item) -> quantity to item.textContent }
            }

    private fun parseXml(file: File): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    /** Descendant [tag] elements paired with their non-empty [keyAttribute]. */
    private fun Element.elementsByKey(tag: String, keyAttribute: String): List<Pair<String, Element>> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { i ->
            val element = nodes.item(i) as Element
            element.getAttribute(keyAttribute).takeIf(String::isNotEmpty)?.let { it to element }
        }
    }

    // endregion

    // region value conversion

    /** Android backslash escapes are not XML; undo them after parsing. */
    private fun unescape(value: String): String = ESCAPE.replace(value) {
        when (val c = it.groupValues[1]) {
            "n" -> "\n"
            "t" -> "\t"
            else -> c
        }
    }

    /** Map Android format specifiers to Apple ones. */
    private fun convertValue(value: String): String = FORMAT_SPECIFIER.replace(value) { match ->
        val (position, type) = match.destructured
        val prefix = if (position.isEmpty()) "%" else "%$position\$"
        when (type) {
            "s" -> "$prefix@"
            "d" -> "${prefix}lld"
            else -> match.value // %% and %f are the same on Apple
        }
    }

    private fun convertPluralVariant(value: String): String = FORMAT_SPECIFIER.replace(value) { match ->
        when (match.groupValues[2]) {
            "s", "d" -> "%arg"
            else -> match.value
        }
    }

    private fun extractArgTypes(value: String): List<ArgType> {
        val specifiers = FORMAT_SPECIFIER.findAll(value)
            .map { it.destructured }
            .filter { (_, type) -> type != "%" }
            .toList()
        val positional = specifiers
            .filter { (position, _) -> position.isNotEmpty() }
            .associate { (position, type) -> position.toInt() to ArgType.of(type) }
        if (positional.isEmpty()) {
            return specifiers.map { (_, type) -> ArgType.of(type) }
        }
        return (1..positional.keys.max()).map { positional[it] ?: ArgType.STRING }
    }

    // endregion

    // region catalog (.xcstrings) writer

    private fun <T> withBaseFallback(
        entries: Map<String, Map<String, T>>,
        locales: Set<String>,
    ): Map<String, Map<String, T>> = entries.mapValues { (_, translations) ->
        val base = translations[BASE_LOCALE]
        if (base == null) {
            translations
        } else {
            locales.associateWith { translations[it] ?: base }
        }
    }

    private fun writeCatalog(
        strings: Map<String, Map<String, String>>,
        plurals: Map<String, Map<String, Map<String, String>>>,
    ) {
        // Stable, alphabetical key order for clean diffs.
        val entries = sortedMapOf<String, Any>()
        strings.forEach { (key, localizations) ->
            entries[key] = catalogEntry(localizations.mapValues { (_, value) -> stringUnit(value) })
        }
        plurals.forEach { (key, localizations) ->
            entries[key] = catalogEntry(localizations.mapValues { (_, variants) -> pluralUnit(variants) })
        }
        val catalog = mapOf(
            "sourceLanguage" to BASE_LOCALE,
            "strings" to entries,
            "version" to "1.0",
        )
        catalogFile.writeIfChanged(
            buildString {
                appendJson(catalog)
                append('\n')
            },
        )
    }

    private fun catalogEntry(localizations: Map<String, Any>) = mapOf(
        "extractionState" to "manual",
        "localizations" to localizations,
    )

    private fun stringUnit(value: String) = mapOf(
        "stringUnit" to mapOf(
            "state" to "translated",
            "value" to value,
        ),
    )

    private fun pluralUnit(variants: Map<String, String>): Map<String, Any> {
        val other = variants["other"]
        val keptVariants = variants.filter { (category, value) ->
            category == "other" || value != other
        }
        return stringUnit("%#@arg1@") + mapOf(
            "substitutions" to mapOf(
                "arg1" to mapOf(
                    "argNum" to 1,
                    "formatSpecifier" to "lld",
                    "variations" to mapOf(
                        "plural" to keptVariants.mapValues { (_, value) -> stringUnit(value) },
                    ),
                ),
            ),
        )
    }

    /** Writes JSON in Xcode's own `.xcstrings` style: 2-space indent, `"key" : value`. */
    private fun StringBuilder.appendJson(value: Any, indent: String = "") {
        if (value !is Map<*, *>) {
            append(JSON.toJson(value))
            return
        }
        append("{\n")
        value.entries.forEachIndexed { index, (key, child) ->
            if (index > 0) append(",\n")
            append(indent).append("  ").append(JSON.toJson(key)).append(" : ")
            appendJson(child!!, "$indent  ")
        }
        append('\n').append(indent).append('}')
    }

    // endregion

    // region Swift writer

    private fun writeSwift(
        stringKeys: Collection<String>,
        stringArgs: Map<String, List<ArgType>>,
        pluralKeys: Collection<String>,
    ) {
        val used = HashSet<String>()
        val text = buildString {
            appendLine("import Foundation")
            appendLine()
            appendLine("public enum L10n {")
            for (key in stringKeys) {
                val name = swiftIdentifier(key, used)
                val args = stringArgs[key].orEmpty()
                val localized = "String(localized: \"$key\", bundle: $SWIFT_BUNDLE)"
                appendLine("    /// $key")
                if (args.isEmpty()) {
                    appendLine("    public static var $name: String {")
                    appendLine("        $localized")
                } else {
                    val params = args.withIndex().joinToString { (i, type) -> "_ a${i + 1}: ${type.swiftType}" }
                    val passed = args.indices.joinToString { "a${it + 1}" }
                    appendLine("    public static func $name($params) -> String {")
                    appendLine("        String(format: $localized, $passed)")
                }
                appendLine("    }")
            }
            for (key in pluralKeys) {
                val name = swiftIdentifier(key, used)
                appendLine("    /// $key (plural)")
                appendLine("    public static func $name(_ count: Int) -> String {")
                appendLine("        String.localizedStringWithFormat(")
                appendLine("            NSLocalizedString(\"$key\", bundle: $SWIFT_BUNDLE, comment: \"\"),")
                appendLine("            count")
                appendLine("        )")
                appendLine("    }")
            }
            appendLine("}")
        }
        swiftFile.writeIfChanged(text)
    }

    private fun swiftIdentifier(key: String, used: MutableSet<String>): String {
        val camel = key.split('_')
            .filter(String::isNotEmpty)
            .mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar(Char::uppercaseChar) }
            .joinToString("")
            .ifEmpty { "_" }
        val base = if (camel.first().isDigit()) "_$camel" else camel

        var unique = base
        var suffix = 2
        while (!used.add(unique)) unique = "$base${suffix++}"
        // Keywords never end in a digit, so only an unsuffixed name needs escaping.
        return if (unique in SWIFT_KEYWORDS) "`$unique`" else unique
    }

    // endregion

    /** Skips identical content, so Xcode doesn't rebuild the committed outputs. */
    private fun RegularFileProperty.writeIfChanged(text: String) {
        val file = get().asFile
        if (file.isFile && file.readText() == text) return
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private enum class ArgType(val swiftType: String) {
        STRING("String"),
        INT("Int"),
        DOUBLE("Double"),
        ;

        companion object {
            fun of(specifier: String): ArgType = when (specifier) {
                "d" -> INT
                "f" -> DOUBLE
                else -> STRING
            }
        }
    }

    companion object {
        private const val BASE_LOCALE = "en"
        private const val SWIFT_BUNDLE = "AppLocalization.shared.bundle"

        private val ESCAPE = Regex("""\\(.)""", RegexOption.DOT_MATCHES_ALL)

        // `%%` is a token too, so the percent after it is never read as a specifier.
        private val FORMAT_SPECIFIER = Regex("""%(?:(\d+)\$)?([sdf%])""")

        private val LANGUAGE = Regex("[a-z]{2,3}")

        private val JSON = JsonGenerator.Options().disableUnicodeEscaping().build()

        private fun appleLocale(dirName: String): String? {
            if (dirName == "values") return BASE_LOCALE
            if (!dirName.startsWith("values-")) return null
            val parts = dirName.removePrefix("values-").split('-')
            val language = parts.first().takeIf { LANGUAGE.matches(it) } ?: return null
            val region = parts.getOrNull(1)?.removePrefix("r")?.takeIf(String::isNotEmpty)

            val mapped = when (language) {
                "iw" -> "he" // legacy Hebrew code
                "in" -> "id" // legacy Indonesian code
                "ji" -> "yi" // legacy Yiddish code
                "no" -> "nb" // Norwegian -> Bokmål on Apple
                else -> language
            }

            if (mapped == "zh") {
                return if (region in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
            }

            // Only keep the region where it disambiguates a language we actually ship
            // in more than one variant; otherwise the bare language code is cleaner.
            return if (region != null && mapped in REGIONAL_LANGUAGES) "$mapped-$region" else mapped
        }

        private val REGIONAL_LANGUAGES = setOf("en", "pt")

        private val SWIFT_KEYWORDS = setOf(
            "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func",
            "import", "init", "inout", "internal", "let", "open", "operator", "private",
            "protocol", "public", "rethrows", "static", "struct", "subscript", "typealias",
            "var", "break", "case", "continue", "default", "defer", "do", "else", "fallthrough",
            "for", "guard", "if", "in", "repeat", "return", "switch", "where", "while", "as",
            "catch", "false", "is", "nil", "super", "self", "Self", "throw", "throws", "true",
            "try", "Any", "Protocol", "Type", "associativity", "convenience", "dynamic",
            "didSet", "final", "get", "infix", "indirect", "lazy", "left", "mutating", "none",
            "nonmutating", "optional", "override", "postfix", "precedence", "prefix", "required",
            "right", "set", "unowned", "weak", "willSet",
        )
    }
}
