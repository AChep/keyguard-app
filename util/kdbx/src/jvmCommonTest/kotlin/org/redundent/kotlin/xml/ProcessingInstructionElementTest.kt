package org.redundent.kotlin.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ProcessingInstructionElementTest {
	@Test
	fun testHashCode() {
		val text = ProcessingInstructionElement("test", emptyMap())

		assertNotEquals(text.text.hashCode(), text.hashCode(), "ProcessingInstructionElement hashcode is not just text.hashCode()")
	}

	@Test
	fun `equals null`() {
		val text = ProcessingInstructionElement("test", emptyMap())

		assertFalse(text.equals(null))
	}

	@Test
	fun `equals different type`() {
		val text = ProcessingInstructionElement("test", emptyMap())
		val other = TextElement("test")

		assertNotEquals(text, other)
	}

	@Test
	fun `equals different text`() {
		val text1 = ProcessingInstructionElement("text1", emptyMap())
		val text2 = ProcessingInstructionElement("text2", emptyMap())

		assertNotEquals(text1, text2)
		assertNotEquals(text2, text1)
	}

	@Test
	fun equals() {
		val text1 = ProcessingInstructionElement("text1", emptyMap())
		val text2 = ProcessingInstructionElement("text1", emptyMap())

		assertEquals(text1, text2)
		assertEquals(text2, text1)
	}

	@Test
	fun `equals attributes same order`() {
		val text1 = ProcessingInstructionElement("text1", linkedMapOf("attr1" to "value1", "attr2" to "value2"))
		val text2 = ProcessingInstructionElement("text1", linkedMapOf("attr1" to "value1", "attr2" to "value2"))

		assertEquals(text1, text2)
		assertEquals(text2, text1)
	}

	@Test
	fun `equals attributes different order`() {
		val text1 = ProcessingInstructionElement("text1", linkedMapOf("attr1" to "value1", "attr2" to "value2"))
		val text2 = ProcessingInstructionElement("text1", linkedMapOf("attr2" to "value2", "attr1" to "value1"))

		assertEquals(text1, text2)
		assertEquals(text2, text1)
	}
}
