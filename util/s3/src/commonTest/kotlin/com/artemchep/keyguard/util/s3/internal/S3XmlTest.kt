package com.artemchep.keyguard.util.s3.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class S3XmlTest {
    @Test
    fun `list result is parsed with the AWS namespace`() {
        val result = S3Xml.parseListBucketResult(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
              <Name>bucket</Name>
              <Prefix>backups/</Prefix>
              <KeyCount>2</KeyCount>
              <IsTruncated>true</IsTruncated>
              <NextContinuationToken>token/+=</NextContinuationToken>
              <Contents>
                <Key>backups/repo.zip</Key>
                <LastModified>2009-10-12T17:50:30.000Z</LastModified>
                <ETag>&quot;fba9dede5f27731c9771645a39863328&quot;</ETag>
                <Size>434234</Size>
              </Contents>
              <CommonPrefixes><Prefix>backups/indexes/</Prefix></CommonPrefixes>
            </ListBucketResult>
            """.trimIndent(),
        )

        assertEquals(true, result.isTruncated)
        assertEquals("token/+=", result.nextContinuationToken)
        val entry = result.objects.single()
        assertEquals("backups/repo.zip", entry.key)
        assertEquals(434234L, entry.size)
        assertEquals(Instant.parse("2009-10-12T17:50:30Z"), entry.lastModified)
        assertEquals("\"fba9dede5f27731c9771645a39863328\"", entry.etag)
        assertEquals(listOf("backups/indexes/"), result.commonPrefixes)
    }

    @Test
    fun `url-encoded keys are decoded only when the encoding type is echoed`() {
        val echoed = S3Xml.parseListBucketResult(
            """
            <ListBucketResult>
              <EncodingType>url</EncodingType>
              <IsTruncated>false</IsTruncated>
              <Contents><Key>a+b%2Bc%20d%C3%A9</Key></Contents>
              <CommonPrefixes><Prefix>x%2Fy/</Prefix></CommonPrefixes>
            </ListBucketResult>
            """.trimIndent(),
        )
        val plain = S3Xml.parseListBucketResult(
            """
            <ListBucketResult>
              <IsTruncated>false</IsTruncated>
              <Contents><Key>a+b%2Bc</Key></Contents>
            </ListBucketResult>
            """.trimIndent(),
        )

        assertEquals("a b+c dé", echoed.objects.single().key)
        assertEquals(listOf("x/y/"), echoed.commonPrefixes)
        assertEquals("a+b%2Bc", plain.objects.single().key)
        assertNull(plain.nextContinuationToken)
    }

    @Test
    fun `a broken percent escape in a url-encoded key is a parse error`() {
        // Invalid UTF-8 would otherwise become U+FFFD, the name of another object.
        for (key in listOf("a%zz", "a%2", "a%+1", "a%FFb", "a%C3", "a%C0%AF", "a%ED%A0%80")) {
            assertFailsWith<IllegalArgumentException>(key) {
                S3Xml.parseListBucketResult(
                    "<ListBucketResult><IsTruncated>false</IsTruncated><EncodingType>url</EncodingType>" +
                        "<Contents><Key>$key</Key></Contents></ListBucketResult>",
                )
            }
        }
    }

    @Test
    fun `a version 1 listing continues after its marker`() {
        fun parse(body: String) = S3Xml.parseListBucketResult(
            "<ListBucketResult><EncodingType>url</EncodingType><IsTruncated>true</IsTruncated>$body</ListBucketResult>",
        )

        val afterKey = parse("<Marker/><Contents><Key>a</Key></Contents><Contents><Key>b%20c</Key></Contents>")
        // Byte order puts a supplementary character after U+FFFD.
        val afterPrefix = parse(
            "<Marker/><Contents><Key>%EF%BF%BD</Key></Contents>" +
                "<CommonPrefixes><Prefix>%F0%9F%98%80/</Prefix></CommonPrefixes>",
        )
        val nextMarker = parse("<Marker/><NextMarker>x%2By/</NextMarker><Contents><Key>a</Key></Contents>")
        val version2 = parse("<Contents><Key>a</Key></Contents><NextContinuationToken>t</NextContinuationToken>")

        assertEquals("b c", afterKey.nextMarker)
        assertEquals("\uD83D\uDE00/", afterPrefix.nextMarker)
        assertEquals("x+y/", nextMarker.nextMarker)
        assertNull(version2.nextMarker)
        assertEquals("t", version2.nextContinuationToken)
    }

    @Test
    fun `error body is parsed`() {
        val error = assertNotNull(
            S3Xml.parseErrorOrNull(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <Error>
                  <Code>RequestTimeTooSkewed</Code>
                  <Message>The difference between the request time and the current time is too large.</Message>
                  <RequestId>4442587FB7D0A2F9</RequestId>
                  <ServerTime>2024-01-01T00:20:00Z</ServerTime>
                </Error>
                """.trimIndent(),
            ),
        )

        assertEquals("RequestTimeTooSkewed", error.code)
        assertEquals(Instant.parse("2024-01-01T00:20:00Z"), error.serverTime)
    }

    @Test
    fun `a malformed or foreign error body is ignored`() {
        assertNull(S3Xml.parseErrorOrNull(""))
        assertNull(S3Xml.parseErrorOrNull("<html><body>Bad gateway</body></html>"))
        // The parser tolerates a truncated body; it just carries no code.
        assertNull(S3Xml.parseErrorOrNull("<Error><Code>")?.code)
    }

    @Test
    fun `DOCTYPE declarations are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            S3Xml.parseListBucketResult(
                """<!DOCTYPE x [<!ENTITY a "b">]><ListBucketResult/>""",
            )
        }
    }

    @Test
    fun `deep nesting is rejected`() {
        val depth = 20_000
        assertFailsWith<IllegalArgumentException> {
            S3Xml.parseListBucketResult(
                "<ListBucketResult>" + "<x>".repeat(depth) + "</x>".repeat(depth) + "</ListBucketResult>",
            )
        }
    }

    @Test
    fun `an unexpected root element is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            S3Xml.parseListBucketResult("<Error><Code>AccessDenied</Code></Error>")
        }
    }
}
