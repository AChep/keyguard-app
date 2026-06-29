package org.redundent.kotlin.xml

/**
 * Similar to a [TextElement] except that the inner text is wrapped inside a `<![CDATA[]]>` tag.
 */
class CDATAElement internal constructor(text: String) : TextElement(text) {
    override fun renderedText(printOptions: PrintOptions): String {
        return "<![CDATA[${text.escapeCData()}]]>"
    }

    override fun equals(other: Any?): Boolean {
        return super.equals(other) && other is CDATAElement
    }

    // Include a class-specific salt so TextElement and CDATAElement differ when they have the same text.
    // the same text
    override fun hashCode(): Int = 31 * super.hashCode() + "CDATAElement".hashCode()

    private fun String.escapeCData(): String {
        val cdataEnd = "]]>"
        val cdataStart = "<![CDATA["

        // Split `cdataEnd` into two pieces so XML parser doesn't recognize it
        return replace(cdataEnd, "]]$cdataEnd$cdataStart>")
    }
}
