package org.redundent.kotlin.xml

/**
 * Base interface for all elements. You shouldn't have to interact with this interface directly.
 * @author Jason Blackwell
 */
interface Element {
	/**
	 * This method handles creating the XML. Used internally.
	 */
	fun render(builder: Appendable, indent: String, printOptions: PrintOptions)
}
