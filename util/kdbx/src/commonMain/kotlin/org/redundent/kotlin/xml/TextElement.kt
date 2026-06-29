package org.redundent.kotlin.xml

/**
 * An element type that has some text in it.
 *
 * For example:
 * ```xml
 * <loc>http://blog.redundent.org</loc>
 * ```
 */
open class TextElement internal constructor(
    val text: String,
    private val unsafe: Boolean = false
) : Element {
	override fun render(builder: Appendable, indent: String, printOptions: PrintOptions) {
		if (text.isEmpty()) {
			return
		}

		val lineEnding = getLineEnding(printOptions)

		builder.append("$indent${renderedText(printOptions)}$lineEnding")
	}

	internal fun renderSingleLine(builder: Appendable, printOptions: PrintOptions) {
		builder.append(renderedText(printOptions))
	}

	internal open fun renderedText(printOptions: PrintOptions): String? {
		return if (unsafe) {
			text
		} else {
			escapeValue(text, printOptions.xmlVersion, printOptions.useCharacterReference)
		}
	}

	override fun equals(other: Any?): Boolean = other is TextElement && other.text == text

	override fun hashCode(): Int = text.hashCode()
}
