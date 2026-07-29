package com.artemchep.keyguard.common.service.tld.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.util.io.toSource
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.TextService
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals

class TldServiceImplTest {
    @Test
    fun `getDomainName ignores comments blanks and wildcard rules`() = runTest {
        val service = createService(
            """

            // comment
            com
            *.ck
            """.trimIndent(),
        )

        assertEquals("example.com", service.getDomainName("www.example.com").bind())
        assertEquals("a.b.ck", service.getDomainName("www.a.b.ck").bind())
    }

    @Test
    fun `getDomainName handles eof without trailing newline`() = runTest {
        val service = createService("com")

        assertEquals("example.com", service.getDomainName("www.example.com").bind())
    }

    @Test
    fun `getDomainName handles exception rules`() = runTest {
        val service = createService(
            """
            jp
            *.kawasaki.jp
            !city.kawasaki.jp
            """.trimIndent(),
        )

        assertEquals("city.kawasaki.jp", service.getDomainName("www.city.kawasaki.jp").bind())
        assertEquals("www.foo.kawasaki.jp", service.getDomainName("www.foo.kawasaki.jp").bind())
    }

    @Test
    fun `getDomainName handles exact exception host`() = runTest {
        val service = createService(
            """
            jp
            *.kawasaki.jp
            !city.kawasaki.jp
            """.trimIndent(),
        )

        assertEquals("city.kawasaki.jp", service.getDomainName("city.kawasaki.jp").bind())
    }

    @Test
    fun `getDomainName handles canonical ck exception rules`() = runTest {
        val service = createService(
            """
            *.ck
            !www.ck
            """.trimIndent(),
        )

        assertEquals("www.ck", service.getDomainName("www.ck").bind())
        assertEquals("www.ck", service.getDomainName("test.www.ck").bind())
        assertEquals("test.foo.ck", service.getDomainName("test.foo.ck").bind())
    }

    @Test
    fun `getDomainName preserves normalization and fallback behavior`() = runTest {
        val service = createService(
            """
            com
            co.uk
            """.trimIndent(),
        )

        assertEquals("example.com", service.getDomainName("  WWW.Example.COM\t").bind())
        assertEquals("example.co.uk", service.getDomainName("LOGIN.EXAMPLE.CO.UK").bind())

        val unknownHost = "  WWW.Example.KEYGUARD-INVALID\t"
        assertEquals(unknownHost, service.getDomainName(unknownHost).bind())
    }

    @Test
    fun `getDomainName falls back to the deepest matching leaf`() = runTest {
        val service = createService(
            """
            com
            special.foo.com
            """.trimIndent(),
        )

        assertEquals("foo.com", service.getDomainName("a.other.foo.com").bind())
        assertEquals("a.special.foo.com", service.getDomainName("a.special.foo.com").bind())
    }

    @Test
    fun `getDomainName preserves empty label behavior`() = runTest {
        val service = createService("com")

        assertEquals("www.example.com.", service.getDomainName("www.example.com.").bind())
        assertEquals("example.com", service.getDomainName(".example.com").bind())
        assertEquals(".com", service.getDomainName("www.example..com").bind())
        assertEquals("example.com", service.getDomainName("www..example.com").bind())
    }

    @Test
    fun `getDomainName computes offsets after unicode lowercasing`() = runTest {
        val service = createService("com")

        assertEquals("example.com", service.getDomainName("\u0130.WWW.EXAMPLE.COM").bind())
    }

    private fun createService(
        publicSuffixList: String,
    ) = TldServiceImpl(
        textService = object : TextService {
            override suspend fun readFromResources(
                fileResource: FileResource,
            ): Source {
                require(fileResource == FileResource.publicSuffixList)
                return publicSuffixList.toSource()
            }

            override fun readFromFile(uri: String): Source = error("Not used in this test.")
        },
        logRepository = object : LogRepository {
            override suspend fun add(
                tag: String,
                message: String,
                level: LogLevel,
            ) = Unit
        },
    )
}
