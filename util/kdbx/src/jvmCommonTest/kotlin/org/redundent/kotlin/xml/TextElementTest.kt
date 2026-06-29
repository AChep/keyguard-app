package org.redundent.kotlin.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class TextElementTest {
	@Test
	fun testHashCode() {
		val text = TextElement("test")

		assertEquals(text.text.hashCode(), text.hashCode())
	}

	@Test
	fun `equals null`() {
		val text = TextElement("test")

		assertFalse(text.equals(null))
	}

	@Test
	fun `equals different type`() {
		val text = TextElement("test")
		val other = Comment("test")

		assertFalse(text.equals(other))
	}

	@Test
	fun `equals different text`() {
		val text1 = TextElement("text1")
		val text2 = TextElement("text2")

		assertNotEquals(text1, text2)
		assertNotEquals(text2, text1)
	}

	@Test
	fun equals() {
		val text1 = TextElement("text1")
		val text2 = TextElement("text1")

		assertEquals(text1, text2)
		assertEquals(text2, text1)
	}
}
