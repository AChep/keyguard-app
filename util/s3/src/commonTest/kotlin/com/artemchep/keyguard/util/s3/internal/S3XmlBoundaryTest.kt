package com.artemchep.keyguard.util.s3.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class S3XmlBoundaryTest {
    @Test
    fun `namespaced CDATA entities and text fragments preserve object names`() {
        val result = S3Xml.parseListBucketResult("""
            <?xml version="1.0"?><!--before-->
            <s:ListBucketResult xmlns:s="urn:s3">
              <s:IsTruncated>false</s:IsTruncated>
              <s:Contents><s:Key><![CDATA[a<]]><!--split-->&amp;&#x1F600;&#233;</s:Key>
                <s:Size> 42 </s:Size><s:ETag> &quot;opaque-2&quot; </s:ETag>
              </s:Contents>
              <s:CommonPrefixes><s:Prefix>dir/</s:Prefix></s:CommonPrefixes>
            </s:ListBucketResult><!--after-->
        """.trimIndent())
        assertEquals("a<&😀é", result.objects.single().key)
        assertEquals(42L, result.objects.single().size)
        assertEquals("\"opaque-2\"", result.objects.single().etag)
        assertEquals(listOf("dir/"), result.commonPrefixes)
    }

    @Test
    fun `listing URL decoding is single pass and tokens are not decoded`() {
        val result = S3Xml.parseListBucketResult("""
            <ListBucketResult><EncodingType>url</EncodingType><IsTruncated>true</IsTruncated>
              <Contents><Key>a+%2B%20%252F%08%F0%9F%98%80</Key></Contents>
              <CommonPrefixes><Prefix>%252F/</Prefix></CommonPrefixes>
              <NextContinuationToken>  a+%2B%252F/==&amp;  </NextContinuationToken>
            </ListBucketResult>
        """.trimIndent())
        assertEquals("a + %2F\u0008😀", result.objects.single().key)
        assertEquals(listOf("%2F/"), result.commonPrefixes)
        assertEquals("  a+%2B%252F/==&  ", result.nextContinuationToken)
    }

    @Test
    fun `missing object keys fail while optional metadata may be absent or malformed`() {
        assertFailsWith<IllegalArgumentException> {
            S3Xml.parseListBucketResult(
                "<ListBucketResult><IsTruncated>false</IsTruncated><Contents/></ListBucketResult>",
            )
        }
        val result = S3Xml.parseListBucketResult(
            "<ListBucketResult><IsTruncated>false</IsTruncated><Contents><Key>key</Key><Size>invalid</Size>" +
                "<LastModified>invalid</LastModified><ETag> </ETag></Contents></ListBucketResult>",
        )
        assertNull(result.objects.single().size)
        assertNull(result.objects.single().lastModified)
        assertNull(result.objects.single().etag)
    }

    @Test
    fun `XML depth boundary accepts supported nesting and rejects the next level`() {
        fun document(depth: Int) =
            "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                "<x>".repeat(depth) + "</x>".repeat(depth) + "</ListBucketResult>"
        assertEquals(emptyList(), S3Xml.parseListBucketResult(document(255)).objects)
        assertFailsWith<IllegalArgumentException> { S3Xml.parseListBucketResult(document(256)) }
    }

    @Test
    fun `malformed attributes multiple roots and unterminated markers are rejected`() {
        for (xml in listOf(
            "<ListBucketResult a=1/>", "<ListBucketResult a/>", "<ListBucketResult a='unterminated>",
            "<ListBucketResult/><ListBucketResult/>", "<ListBucketResult><!--unfinished",
            "<ListBucketResult><![CDATA[unfinished", "<ListBucketResult><?unfinished",
        )) {
            assertFailsWith<IllegalArgumentException>(xml) { S3Xml.parseListBucketResult(xml) }
        }
    }

    @Test
    fun `bounded incomplete error documents can retain a completed error code`() {
        val error = S3Xml.parseErrorOrNull("<Error><Code>AccessDenied</Code><Message>unfinished")
        assertEquals("AccessDenied", error?.code)
        assertNull(S3Xml.parseErrorOrNull("<!DOCTYPE x><Error/>"))
    }

    @Test
    fun `malformed entities and characters cannot become object keys`() {
        for (key in listOf(
            "a&unknown;b", "a&b", "a&#0;b", "a&#xD800;b", "a&#8;b", "a&#xFFFE;b",
            "a&#x110000;b", "a&#+10;b", "a&#x+41;b", "a&#X41;b", "a\u0008b", "a\uFFFFb",
            "a\uD800b", "a\uDC00b", "a]]>b", "<![CDATA[a\u0008b]]>",
        )) {
            assertFailsWith<IllegalArgumentException>(key) {
                S3Xml.parseListBucketResult(listWithKey(key))
            }
        }
        assertEquals(
            "\t\n\r&😀",
            S3Xml.parseListBucketResult(listWithKey("&#9;&#10;&#13;&amp;&#x1F600;")).objects.single().key,
        )
    }

    @Test
    fun `literal XML line endings normalize before character references are decoded`() {
        val key = "a\r\nb\rc<![CDATA[d\r\ne\rf]]>&#13;g"
        val result = S3Xml.parseListBucketResult(listWithKey(key))
        assertEquals("a\nb\ncd\ne\nf\rg", result.objects.single().key)
    }

    @Test
    fun `truncation accepts XML booleans but missing or invalid flags cannot silently end pagination`() {
        for ((flag, expected) in listOf("true" to true, "false" to false, "1" to true, "0" to false)) {
            val result = S3Xml.parseListBucketResult(
                "<ListBucketResult><IsTruncated>$flag</IsTruncated></ListBucketResult>",
            )
            assertEquals(expected, result.isTruncated, flag)
        }
        for (flag in listOf("", "<IsTruncated/>", "<IsTruncated>tru</IsTruncated>")) {
            assertFailsWith<IllegalArgumentException>(flag) {
                S3Xml.parseListBucketResult(
                    "<ListBucketResult>$flag<NextContinuationToken>next</NextContinuationToken>" +
                        "<Contents><Key>one</Key></Contents></ListBucketResult>",
                )
            }
        }
    }

    @Test
    fun `unescaped attribute markup and duplicate attributes are rejected`() {
        for (attributes in listOf("a='<'", "a='one' a='two'")) {
            assertFailsWith<IllegalArgumentException>(attributes) {
                S3Xml.parseListBucketResult(
                    "<ListBucketResult $attributes><IsTruncated>false</IsTruncated></ListBucketResult>",
                )
            }
        }
    }

    private fun listWithKey(key: String): String =
        "<ListBucketResult><IsTruncated>false</IsTruncated><Contents><Key>$key</Key></Contents></ListBucketResult>"
}
