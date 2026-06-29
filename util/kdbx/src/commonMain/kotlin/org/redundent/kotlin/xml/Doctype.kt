package org.redundent.kotlin.xml

class Doctype(
    private val name: String,
    private val systemId: String? = null,
    private val publicId: String? = null
) : Element {
    override fun render(builder: Appendable, indent: String, printOptions: PrintOptions) {
        builder.append("<!DOCTYPE $name")

        val publicIdSet = publicId != null
        val systemIdSet = systemId != null

        if (publicIdSet) {
            builder.append(" PUBLIC \"$publicId\"")
        }

        if (systemIdSet) {
            if (!publicIdSet) {
                builder.append(" SYSTEM")
            }
            builder.append(" \"$systemId\"")
        }

        builder.appendLine(">")
    }
}
