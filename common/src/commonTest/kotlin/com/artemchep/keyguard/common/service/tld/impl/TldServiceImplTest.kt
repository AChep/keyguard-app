package com.artemchep.keyguard.common.service.tld.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.toSource
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
