package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.file.PureFileService
import com.artemchep.keyguard.common.service.text.TextService
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadWordlistFromFileImplTest {
    private val fileService = PureFileService()
    private val textService = object : TextService {
        override suspend fun readFromResources(
            fileResource: FileResource,
        ): Source = error("Not used in this test.")

        override fun readFromFile(uri: String): Source = fileService.readFromFile(uri)
    }

    private val useCase = ReadWordlistFromFileImpl(
        textService = textService,
    )

    @Test
    fun `invoke filters blank and commented lines from file`() = runTest {
        val file = createTempDirectory("read-wordlist-file")
            .resolve("wordlist.txt")
        file.writeText(
            """
            # comment
            alpha

            ; comment
            beta
            / comment
            - comment
            """.trimIndent(),
        )

        val actual = useCase(file.toUri().toString()).bind()

        assertEquals(listOf("alpha", "beta"), actual)
    }

    @Test
    fun `invoke handles final line without trailing newline`() = runTest {
        val root = createTempDirectory("read-wordlist-file-eof")
        val newlineTerminatedFile = root.resolve("with-newline.txt")
        val eofTerminatedFile = root.resolve("without-newline.txt")
        val contentWithNewline = "alpha\nbeta\n"
        val contentWithoutNewline = "alpha\nbeta"
        newlineTerminatedFile.writeText(contentWithNewline)
        eofTerminatedFile.writeText(contentWithoutNewline)

        val withNewline = useCase(newlineTerminatedFile.toUri().toString()).bind()
        val withoutNewline = useCase(eofTerminatedFile.toUri().toString()).bind()

        assertEquals(withNewline, withoutNewline)
        assertEquals(listOf("alpha", "beta"), withoutNewline)
    }
}
