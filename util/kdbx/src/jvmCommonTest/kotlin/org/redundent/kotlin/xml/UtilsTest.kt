package org.redundent.kotlin.xml

import kotlin.test.Test
import kotlin.test.assertEquals

class UtilsTest {
	@Test
	fun escapeValue10() {
		val unescapedValue = "\u000b\u000c"

		val escapedValue = escapeValue(unescapedValue, XmlVersion.V10)

		assertEquals("", escapedValue, "1.0 escapes \\u000b and \\u000c to empty string")
	}

	@Test
	fun escapeValue11() {
		val unescapedValue = "\u000b\u000c"

		val escapedValue = escapeValue(unescapedValue, XmlVersion.V11)

		assertEquals("&#11;&#12;", escapedValue, "1.1 escapes \\u000b and \\u000c to &#11; and &#12;")
	}
}
