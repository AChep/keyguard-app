@file:Suppress("MemberVisibilityCanBePrivate")

package org.redundent.kotlin.xml

/**
 * Represents Xml namespace (`xmlns`).
 */
data class Namespace(
	/**
	 * The name or prefix of the namespace.
	 */
	val name: String,
	/**
	 * The value/uri/url of the namespace.
	 */
	val value: String
) {
    constructor(value: String) : this("", value)

	val isDefault: Boolean = name.isEmpty()

	val fqName: String = if (isDefault) "xmlns" else "xmlns:$name"

	override fun toString(): String = "$fqName=\"$value\""
}
