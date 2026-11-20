package com.artemchep.keyguard.buildplugins.resources

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The locale code the generators give to the base `values` directory. */
internal const val BASE_STRINGS_LOCALE = "en"

/**
 * The `strings.xml` and `plurals.xml` resources of every locale, with Android
 * escapes undone but format specifiers left as they are.
 */
internal class StringResources(
    /** Every locale that has a strings or plurals file, sorted. */
    val locales: Set<String>,
    /** key -> (locale -> value) */
    val strings: Map<String, Map<String, String>>,
    /** key -> (locale -> (CLDR category -> value)) */
    val plurals: Map<String, Map<String, Map<String, String>>>,
)

/**
 * Reads the string resources of [resourcesDir]. [localeOf] maps a `values*`
 * directory name to the consumer's locale code, or to `null` to skip it. When
 * two directories map to the same locale, the later one, in name order, wins
 * per key.
 */
internal fun readStringResources(
    resourcesDir: File,
    localeOf: (directoryName: String) -> String?,
): StringResources {
    val locales = sortedSetOf<String>()
    val strings = sortedMapOf<String, MutableMap<String, String>>()
    val plurals = sortedMapOf<String, MutableMap<String, MutableMap<String, String>>>()
    for (name in localeDirectoryNames(resourcesDir)) {
        val locale = localeOf(name) ?: continue
        val dir = resourcesDir.resolve(name)

        dir.resolve("strings.xml").takeIf(File::isFile)?.let { file ->
            locales += locale
            parseStrings(file).forEach { (key, raw) ->
                strings.getOrPut(key) { sortedMapOf() }[locale] = unescapeAndroidString(raw)
            }
        }

        dir.resolve("plurals.xml").takeIf(File::isFile)?.let { file ->
            locales += locale
            parsePlurals(file).forEach { (key, items) ->
                val byCategory = plurals.getOrPut(key) { sortedMapOf() }
                    .getOrPut(locale) { linkedMapOf() }
                items.forEach { (category, raw) ->
                    byCategory[category] = unescapeAndroidString(raw)
                }
            }
        }
    }
    return StringResources(locales, strings, plurals)
}

/**
 * Gives every locale a whole entry: a locale without its own translation gets
 * the [BASE_STRINGS_LOCALE] one. A translated entry is never mixed with the base
 * one, so a translated plural keeps only its own categories.
 */
internal fun <T> withBaseFallback(
    entries: Map<String, Map<String, T>>,
    locales: Set<String>,
): Map<String, Map<String, T>> = entries.mapValues { (_, translations) ->
    val base = translations[BASE_STRINGS_LOCALE]
    if (base == null) {
        translations
    } else {
        locales.associateWith { translations[it] ?: base }
    }
}

/**
 * An Android format specifier: `%s`, `%d`, `%f`, optionally positional
 * (`%1$s`). `%%` is a token too, so the percent after it is never read as a
 * specifier.
 */
internal val ANDROID_FORMAT_SPECIFIER = Regex("""%(?:(\d+)\$)?([sdf%])""")

private val ANDROID_ESCAPE = Regex("""\\(.)""", RegexOption.DOT_MATCHES_ALL)

/** Android backslash escapes are not XML; undo them after parsing. */
internal fun unescapeAndroidString(value: String): String = ANDROID_ESCAPE.replace(value) {
    when (val c = it.groupValues[1]) {
        "n" -> "\n"
        "t" -> "\t"
        else -> c
    }
}

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
