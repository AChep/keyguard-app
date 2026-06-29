package org.redundent.kotlin.xml

import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmlBuilderTest : TestBase() {
    @Test
    fun basicTest() {
        val urlset = xml("urlset") {
            xmlns = "https://www.sitemaps.org/schemas/sitemap/0.9"

            for (i in 0..2) {
                element("url") {
                    element("loc") {
                        text("https://google.com/$i")
                    }
                }
            }
        }

        validate(urlset)
    }

    @Test
    fun customNamespaces() {
        val root = xml("root") {
            xmlns = "https://someurl.org"
            namespace("t", "https://t.org")

            element("t:element") {
                text("Test")
            }

            element("p") {
                xmlns = "https://t.co"
            }

            element("d:p") {
                namespace("d", "https://b.co")
            }
        }

        validate(root)
    }

    @Test
    fun notPrettyFormatting() {
        val root = xml("root") {
            element("element") {
                text("Hello")
            }
            element("otherElement") {
                text("Test")
            }
        }

        validate(root, prettyFormat = false)
    }

    @Test
    fun zeroSpaceIndent() {
        val root = xml("root") {
            element("element") {
                text("Hello")
            }
            element("otherElement") {
                text("Test")
            }
        }

        validate(root, PrintOptions(indent = ""))
    }

    @Test
    fun zeroSpaceIndentNoPrettyFormatting() {
        val root = xml("root") {
            element("element") {
                text("Hello")
            }
            element("otherElement") {
                text("Test")
            }
        }

        validate(root, PrintOptions(pretty = false, indent = ""))
    }

    @Test
    fun singleLineTextElement() {
        val root = xml("root") {
            element("element") {
                text("Hello")
            }
            element("otherElement") {
                text("Test")
            }
        }

        validate(root, PrintOptions(pretty = true, singleLineTextElements = true))
    }

    @Test
    fun singleLineCDATAElement() {
        val root = xml("root") {
            element("element") {
                cdata("Some & xml")
            }
        }

        validate(root, PrintOptions(pretty = true, singleLineTextElements = true))
    }

    @Test
    fun singleLineProcessingInstructionElement() {
        val root = xml("root") {
            element("element") {
                processingInstruction("SomeProcessingInstruction")
            }
        }

        validate(root, PrintOptions(pretty = true, singleLineTextElements = true))
    }

    @Test
    fun singleLineProcessingInstructionElementWithAttributes() {
        val root = xml("root") {
            element("element") {
                processingInstruction("SomeProcessingInstruction", "key" to "value")
            }
        }

        validate(root, PrintOptions(pretty = true, singleLineTextElements = true))
    }

    @Test
    fun globalProcessingInstructionElement() {
        val root = xml("root") {
            globalProcessingInstruction(
                "xml-stylesheet",
                "key" to "value",
                "href" to "http://blah"
            )

            element("element") {
                globalProcessingInstruction("test")
            }
        }

        validate(root, PrintOptions(pretty = true, singleLineTextElements = true))
    }

    @Test
    fun comment() {
        val root = xml("root") {
            comment("my comment -->")
            element("someNode") {
                text("value")
            }
        }

        validate(root)
    }

    @Test
    fun noSelfClosingTag() {
        val root = xml("root") {
            element("element")
        }

        validate(root, PrintOptions(useSelfClosingTags = false))
    }

    @Test
    fun multipleAttributes() {
        val root = xml("root") {
            element("test") {
                attribute("key", "value")
                attribute("otherAttr", "hello world")
            }
            element("attributes") {
                attributes(
                    "test" to "value",
                    "key" to "pair"
                )
            }
        }

        validate(root)
    }

    @Test
    fun emptyRoot() {
        validate(xml("root"))
    }

    @Test
    fun emptyElement() {
        validate(
            xml("root") {
                element("test")
            }
        )
    }

    @Test
    fun cdata() {
        val root = xml("root") {
            cdata("Some & xml")
        }

        validate(root)
    }

    @Test
    fun cdataNesting() {
        val root = xml("root") {
            cdata("<![CDATA[Some & xml]]>")
        }

        validate(root)
    }

    @Test
    fun processingInstruction() {
        val root = xml("root") {
            processingInstruction("SomeProcessingInstruction")
        }

        validate(root)
    }

    @Test
    fun updateAttribute() {
        val root = xml("root") {
            attribute("key", "value")
        }

        root["key"] = "otherValue"

        validate(root)
    }

    @Test
    fun xmlEncode() {
        val root = xml("root") {
            text("&<>")
        }

        validate(root)
    }

    @Test
    fun elementValue() {
        val root = xml("root") {
            element("name", "value")
        }

        validate(root)
    }

    @Test
    fun elementAsString() {
        val root = xml("root") {
            element("name") {
                text("value")
            }
        }

        validate(root)
    }

    @Test
    fun elementAsStringWithAttributes() {
        validate(
            xml("root") {
                element("name") {
                    attributes("attr" to "value", "attr2" to "other")
                }
            }
        )
    }

    @Test
    fun elementAsStringWithAttributesAndContent() {
        validate(
            xml("root") {
                element("name") {
                    attributes("attr" to "value")
                    text("Content")
                }
            }
        )
    }

    @Test
    fun attributes() {
        val xmlns = "testing"
        val value = "value"

        val xml = xml("root") {
            this.xmlns = xmlns
            attribute("attr", value)
        }

        assertEquals(xmlns, xml.xmlns, "xmlns is correct")
        assertNotNull(xml["attr"], "attr is not null")
        assertEquals(value, xml["attr"]!!, "attr getting is correct")

        // Update the attr value
        xml["attr"] = "something else"
        assertEquals("something else", xml["attr"]!!, "attr value is updated")

        // Remove the
        xml.xmlns = null
        assertNull(xml.xmlns, "xmlns is removed")

        xml["attr"] = null
        assertFalse(xml.attributes.containsKey("attr"))
        assertNull(xml["attr"], "attr value is null")
    }

    @Test
    fun quoteInAttribute() {
        val root = xml("root") {
            attribute("attr", "My \" Attribute value '")
        }

        validate(root)
    }

    @Test
    fun specialCharInAttribute() {
        val root = xml("root") {
            attribute("attr", "& < > \" '")
        }

        validate(root)
    }

    @Test
    fun invalidElementName() {
        val root = xml("invalid root")

        assertFailsWith<SAXException> {
            validateXml(root.toString())
        }
    }

    @Test
    fun invalidAttributeName() {
        val root = xml("root") {
            attribute("invalid name", "")
        }

        assertFailsWith<SAXException> {
            validateXml(root.toString())
        }
    }

    @Test
    fun filterFunctions() {
        val xml = xml("root") {
            element("child1") {
                element("other")
            }
            element("child2")
            element("multiple")
            element("multiple")
        }

        val child1 = xml.filter("child1")
        assertEquals(1, child1.size, "filter returned one element")

        val hasChild = xml.filter { it.nodeName == "child1" && it.exists("other") }
        assertEquals(1, hasChild.size, "filter with exists returned one element")

        val multiple = xml.filter("multiple")
        assertEquals(2, multiple.size, "filter with multiple returned two element")

        assertNull(xml.firstOrNull("junk"), "firstOrNull returned null")
        assertNotNull(xml.firstOrNull("child1"), "firstOrNull returned element")

        assertFailsWith(NoSuchElementException::class) {
            xml.first("junk")
        }

        assertTrue("element exists") { xml.exists("child1") }
        assertFalse("element doesn't exists") { xml.exists("junk") }
    }

    @Test
    fun addElement() {
        val root = xml("root") {
            element("a")
        }

        root.addElement(node("b"))

        validate(root)
    }

    @Test
    fun removeElement() {
        val root = xml("root") {
            element("a")
            element("b")
        }

        root.removeElement(root.first("b"))

        validate(root)
    }

    @Test
    fun addElementAfter() {
        val root = xml("root") {
            element("a")
            element("b")
        }

        root.addElementAfter(node("c"), root.first("a"))

        validate(root)
    }

    @Test
    fun addElementAfterLastChild() {
        val root = xml("root") {
            element("a")
            element("b")
        }

        root.addElementAfter(node("c"), root.first("b"))

        validate(root)
    }

    @Test
    fun addElementAfterNonExistent() {
        val root = xml("root") {
            element("a")
            element("b")
        }

        assertFailsWith<IllegalArgumentException> {
            root.addElementAfter(node("c"), node("d"))
        }
    }

    @Test
    fun addElementBefore() {
        val root = xml("root") {
            element("a")
            element("b")
        }

        root.addElementBefore(node("c"), root.first("b"))

        validate(root)
    }

    @Test
    fun addElementBeforeNonExistent() {
        val root = xml("root") {
            element("a")
            element("b")
        }

        assertFailsWith<IllegalArgumentException> {
            root.addElementBefore(node("c"), node("d"))
        }
    }

    @Test
    fun replaceElement() {
        val root = xml("root") {
            element("a")
            element("b")
        }

        root.replaceElement(root.first("b"), node("c"))

        validate(root)
    }

    @Test
    fun parseAndVerify() {
        val xmlns = "https://blog.redundent.org"
        val value = "value"
        val input =
            ByteArrayInputStream("<root xmlns=\"$xmlns\"><child>$value</child></root>".toByteArray())

        val root = parse(input)

        assertEquals("root", root.nodeName, "root element nodeName is correct")
        assertEquals(xmlns, root.xmlns, "root xmlns is correct")

        val children = root.children
        assertEquals(1, children.size, "root has 1 child")
        assertTrue(children[0] is Node, "child is a node")

        val child = children.first() as Node
        assertTrue(child.children[0] is TextElement, "element is text")
        assertEquals(value, (child.children[0] as TextElement).text)
    }

    @Test
    fun parseCData() = parseTest()

    @Test
    fun parseCDataWhitespace() = parseTest()

    @Test
    fun parseCustomNamespaces() = parseTest()

    @Test
    fun parseMultipleAttributes() = parseTest()

    @Test
    fun parseBasicTest() = parseTest()

    @Test
    fun parseXmlEncode() = parseTest()

    private fun parseTest() {
        val input = getInputStream()
        val xml = parse(input)

        validateTest(xml)
    }

    @Test
    fun checkIncludeXmlPrologFlag() {
        val node = xml("test")
        assertFalse(node.includeXmlProlog, "prolog is false")

        node.encoding = "UTF-8"
        assertTrue(node.includeXmlProlog, "prolog is included")
    }

    @Test
    fun encoding() {
        val xml = xml("test", encoding = "UTF-16").toString(prettyFormat = false)

        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-16\"?><test/>", xml)
    }

    @Test
    fun xmlVersion() {
        for (version in XmlVersion.values()) {
            val xml = xml("test", version = version).toString(prettyFormat = false)
            assertEquals("<?xml version=\"${version.value}\" encoding=\"UTF-8\"?><test/>", xml)
        }
    }

    @Test
    fun characterReference() {
        val root = xml("root") {
            element("element") {
                text("Hello & Goodbye")
            }
            element("otherElement") {
                text("Test")
            }
        }

        validate(
            root,
            PrintOptions(pretty = true, singleLineTextElements = true, useCharacterReference = true)
        )
    }

    @Test
    fun selfClosingTag() {
        for (text in arrayOf("", null)) {
            val root = xml("root") {
                element("element") {
                    if (text != null) {
                        text(text)
                    }
                }
            }

            validate(root, PrintOptions(pretty = true, useSelfClosingTags = true))
        }
    }

    @Test
    fun doctypeSimple() {
        val root = xml("root") {
            doctype()
        }

        validate(root)
    }

    @Test
    fun advancedNamespaces() {
        val ns1 = Namespace("a", "https://ns1.org")
        val ns2 = Namespace("https://ns2.org")
        val ns3 = Namespace("b", "https://ns3.org")
        val ns4 = Namespace("c", "https://ns4.org")
        val ns5 = Namespace("d", "https://ns5.org")
        val ns6 = Namespace("e", "https://ns6.org")

        val root = xml("root", namespace = ns1) {
            namespace(ns2)
            element("node", ns3) {
                attribute("attr1", "value")
                attribute("attr2", "value", ns4)
            }

            element("child") {
                attributes(
                    ns5,
                    "key1" to "value1",
                    "key2" to "value2"
                )
                attributes(
                    Attribute("key3", "value3", ns6),
                    Attribute("key4", "value4", ns1)
                )

                element("sub", ns5) {
                    attributes(Attribute("key5", "value5", ns6))
                }
            }
        }

        validate(root)
    }

    @Test
    fun unsafeAttributeValue() {
        val root = xml("root") {
            unsafeText("&#123;")
            attribute("test", unsafe("&#456;"))
        }

        validate(root)
    }

    @Test
    fun emptyString() {
        val root = xml("root") {
            element("a")
            text(" ")
            element("b")
        }

        validate(root, prettyFormat = false)
    }

    @Test
    fun whitespace() {
        val root = xml("root") {
            element("a") { text(" ") }
            element("b") { text("\n") }
        }

        validate(root, prettyFormat = false)
    }
}
