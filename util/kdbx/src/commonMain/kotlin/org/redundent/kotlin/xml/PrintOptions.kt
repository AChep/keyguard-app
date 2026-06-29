package org.redundent.kotlin.xml

class PrintOptions(
	/**
	 * Whether to print newlines and tabs while rendering the document.
	 */
	val pretty: Boolean = true,
	/**
	 * Whether to print a single text element on the same line.
	 *
	 * ```
	 * <element>
	 *     text value
	 * </element>
	 * ```
	 *
	 * vs
	 *
	 * ```
	 * <element>text value</element>
	 * ```
	 */
	val singleLineTextElements: Boolean = false,
	/**
	 * Whether to use "self closing" tags for empty elements.
	 *
	 * ```
	 * <element></element>
	 * ```
	 *
	 * vs
	 *
	 * ```
	 * <element />
	 * ```
	 */
	val useSelfClosingTags: Boolean = true,
	/**
	 * Whether to use escaped character or character reference
	 *
	 * If false: `'` becomes `&apos;`
	 *
	 * If `true`: `'` becomes `&#39;`
	 */
	val useCharacterReference: Boolean = false,
	/**
	 * Changes the indent for new lines when [pretty] is enabled. The option has no effect when
	 * [pretty] is set to `false`. The default uses one tab `\t`.
	 */
	val indent: String = "\t"
) {
	internal var xmlVersion: XmlVersion = XmlVersion.V10
}
