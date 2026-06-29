package org.redundent.kotlin.xml

/**
 * Similar to a [TextElement] except that the inner text is wrapped inside `<??>` tag.
 */
class ProcessingInstructionElement internal constructor(
    text: String,
    private val attributes: Map<String, String>
) : TextElement(text) {
    override fun renderedText(printOptions: PrintOptions): String {
        return "<?$text${renderAttributes()}?>"
    }

    private fun renderAttributes(): String {
        if (attributes.isEmpty()) {
            return ""
        }

        return " " + attributes.entries.joinToString(" ") {
            "${it.key}=\"${it.value}\""
        }
    }

    override fun equals(other: Any?): Boolean {
        if (!super.equals(other) || other !is ProcessingInstructionElement) {
            return false
        }

        return attributes == other.attributes
    }

    override fun hashCode(): Int = 31 * super.hashCode() + attributes.hashCode()
}
