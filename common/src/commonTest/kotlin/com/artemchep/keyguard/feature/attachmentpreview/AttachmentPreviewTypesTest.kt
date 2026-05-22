package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.common.model.AttachmentPreviewKind
import com.artemchep.keyguard.common.model.AttachmentPreviewLimits
import com.artemchep.keyguard.common.model.AttachmentPreviewPolicy
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.attachmentPreviewKindByFileName
import com.artemchep.keyguard.common.model.isAttachmentPreviewSupported
import com.artemchep.keyguard.common.model.isMarkdownAttachmentPreview
import com.artemchep.keyguard.common.usecase.impl.CanPreviewAttachmentImpl
import com.artemchep.keyguard.platform.Platform
import dev.snipme.highlights.model.SyntaxLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AttachmentPreviewTypesTest {
    @Test
    fun `image extensions are previewable`() {
        assertEquals(AttachmentPreviewKind.Image, attachmentPreviewKindByFileName("photo.PNG"))
        assertEquals(AttachmentPreviewKind.Image, attachmentPreviewKindByFileName("archive/image.webp"))
    }

    @Test
    fun `text extensions are previewable`() {
        assertEquals(AttachmentPreviewKind.Text, attachmentPreviewKindByFileName("notes.txt"))
        assertEquals(AttachmentPreviewKind.Text, attachmentPreviewKindByFileName("config.properties"))
        assertEquals(AttachmentPreviewKind.Text, attachmentPreviewKindByFileName("src/Main.kt"))
        assertEquals(AttachmentPreviewKind.Text, attachmentPreviewKindByFileName("script.sh"))
        assertEquals(AttachmentPreviewKind.Text, attachmentPreviewKindByFileName(".env.local"))
        assertEquals(AttachmentPreviewKind.Text, attachmentPreviewKindByFileName("config.toml"))
        assertEquals(AttachmentPreviewKind.Text, attachmentPreviewKindByFileName("Dockerfile"))
        assertEquals(AttachmentPreviewKind.Text, attachmentPreviewKindByFileName("README"))
        assertEquals(AttachmentPreviewKind.Text, attachmentPreviewKindByFileName("LICENSE"))
    }

    @Test
    fun `textextensions text extensions are previewable`() {
        listOf(
            "ada",
            "asp",
            "diff",
            "dockerignore",
            "eml",
            "json5",
            "mdx",
            "npmrc",
            "pbxproj",
            "sublime-settings",
            "tmlanguage",
            "vue",
            "x-php",
        ).forEach { extension ->
            assertEquals(
                AttachmentPreviewKind.Text,
                attachmentPreviewKindByFileName("sample.$extension"),
                extension,
            )
        }
    }

    @Test
    fun `markdown extensions are previewable separately`() {
        assertEquals(AttachmentPreviewKind.Markdown, attachmentPreviewKindByFileName("README.md"))
        assertEquals(AttachmentPreviewKind.Markdown, attachmentPreviewKindByFileName("notes.markdown"))
        assertEquals(true, isAttachmentPreviewSupported("README.md"))
        assertEquals(true, isMarkdownAttachmentPreview("README.md"))
        assertEquals(true, isMarkdownAttachmentPreview("notes.markdown"))
        assertEquals(false, isMarkdownAttachmentPreview("notes.txt"))
    }

    @Test
    fun `unsupported extensions are not previewable`() {
        assertNull(attachmentPreviewKindByFileName("archive.zip"))
        assertNull(attachmentPreviewKindByFileName("backup.sqlite"))
        assertNull(attachmentPreviewKindByFileName("unknown.blob"))
    }

    @Test
    fun `syntax language is inferred from source extensions`() {
        assertEquals(SyntaxLanguage.KOTLIN, attachmentPreviewSyntaxLanguageByFileName("Main.kt"))
        assertEquals(SyntaxLanguage.JAVA, attachmentPreviewSyntaxLanguageByFileName("Main.java"))
        assertEquals(SyntaxLanguage.JAVASCRIPT, attachmentPreviewSyntaxLanguageByFileName("app.mjs"))
        assertEquals(SyntaxLanguage.TYPESCRIPT, attachmentPreviewSyntaxLanguageByFileName("app.tsx"))
        assertEquals(SyntaxLanguage.PYTHON, attachmentPreviewSyntaxLanguageByFileName("manage.py"))
        assertEquals(SyntaxLanguage.SHELL, attachmentPreviewSyntaxLanguageByFileName("install.sh"))
        assertEquals(SyntaxLanguage.GO, attachmentPreviewSyntaxLanguageByFileName("main.go"))
        assertEquals(SyntaxLanguage.RUST, attachmentPreviewSyntaxLanguageByFileName("lib.rs"))
        assertEquals(SyntaxLanguage.SWIFT, attachmentPreviewSyntaxLanguageByFileName("App.swift"))
        assertEquals(SyntaxLanguage.PHP, attachmentPreviewSyntaxLanguageByFileName("index.php"))
        assertEquals(SyntaxLanguage.RUBY, attachmentPreviewSyntaxLanguageByFileName("task.rb"))
        assertEquals(SyntaxLanguage.C, attachmentPreviewSyntaxLanguageByFileName("main.c"))
        assertEquals(SyntaxLanguage.CPP, attachmentPreviewSyntaxLanguageByFileName("main.cpp"))
        assertEquals(SyntaxLanguage.CSHARP, attachmentPreviewSyntaxLanguageByFileName("Program.cs"))
        assertEquals(SyntaxLanguage.DART, attachmentPreviewSyntaxLanguageByFileName("main.dart"))
        assertEquals(SyntaxLanguage.PERL, attachmentPreviewSyntaxLanguageByFileName("script.pl"))
    }

    @Test
    fun `syntax language falls back for config and plain text files`() {
        assertEquals(SyntaxLanguage.DEFAULT, attachmentPreviewSyntaxLanguageByFileName("data.json"))
        assertEquals(SyntaxLanguage.DEFAULT, attachmentPreviewSyntaxLanguageByFileName("config.toml"))
        assertEquals(SyntaxLanguage.DEFAULT, attachmentPreviewSyntaxLanguageByFileName("Dockerfile"))
        assertEquals(SyntaxLanguage.DEFAULT, attachmentPreviewSyntaxLanguageByFileName("README"))
        assertEquals(SyntaxLanguage.DEFAULT, attachmentPreviewSyntaxLanguageByFileName(".env.local"))
        assertNull(attachmentPreviewSyntaxLanguageByFileName("archive.zip"))
    }

    @Test
    fun `preview text index exposes logical lines`() {
        assertEquals(
            listOf("first", "second"),
            "first\nsecond".previewLines(),
        )
        assertEquals(
            listOf("first", ""),
            "first\n".previewLines(),
        )
        assertEquals(
            listOf(""),
            "".previewLines(),
        )
        assertEquals(
            listOf("first", "second", "third"),
            "first\r\nsecond\nthird".previewLines(),
        )
    }

    @Test
    fun `preview text index exposes logical line lengths`() {
        assertEquals(
            listOf(5, 6),
            "first\nsecond".previewLineLengths(),
        )
        assertEquals(
            listOf(5, 0),
            "first\n".previewLineLengths(),
        )
        assertEquals(
            listOf(0),
            "".previewLineLengths(),
        )
        assertEquals(
            listOf(5, 0, 5),
            "first\r\n\nthird".previewLineLengths(),
        )
    }

    @Test
    fun `preview text index exposes max line length`() {
        assertEquals(6, "first\nsecond".previewMaxLineLength())
        assertEquals(5, "first\n".previewMaxLineLength())
        assertEquals(0, "".previewMaxLineLength())
        assertEquals(5, "first\r\n\nthird".previewMaxLineLength())
    }

    @Test
    fun `route is created for remote previewable attachments on android and desktop`() {
        val attachment = remoteAttachment(fileName = "diagram.png")

        val androidRoute = assertNotNull(
            createAttachmentPreviewRouteOrNull(
                localCipherId = "local-cipher",
                remoteCipherId = "remote-cipher",
                attachment = attachment,
                canPreviewAttachment = CanPreviewAttachmentImpl(
                    platform = Platform.Mobile.Android(
                        isChromebook = false,
                        isWatch = false,
                        sdk = 35,
                    ),
                ),
            ),
        )
        assertEquals(1L, androidRoute.args.encryptedSize)

        val desktopRoute = assertNotNull(
            createAttachmentPreviewRouteOrNull(
                localCipherId = "local-cipher",
                remoteCipherId = "remote-cipher",
                attachment = attachment,
                canPreviewAttachment = CanPreviewAttachmentImpl(
                    platform = Platform.Desktop.MacOS,
                ),
            ),
        )
        assertEquals(1L, desktopRoute.args.encryptedSize)
    }

    @Test
    fun `route is not created for local unsupported ios or too large attachments`() {
        assertNull(
            createAttachmentPreviewRouteOrNull(
                localCipherId = "local-cipher",
                remoteCipherId = "remote-cipher",
                attachment = DSecret.Attachment.Local(
                    id = "attachment",
                    url = "file:///tmp/file.txt",
                    fileName = "file.txt",
                ),
                canPreviewAttachment = CanPreviewAttachmentImpl(
                    platform = Platform.Desktop.MacOS,
                ),
            ),
        )
        assertNull(
            createAttachmentPreviewRouteOrNull(
                localCipherId = "local-cipher",
                remoteCipherId = "remote-cipher",
                attachment = remoteAttachment(fileName = "archive.zip"),
                canPreviewAttachment = CanPreviewAttachmentImpl(
                    platform = Platform.Desktop.MacOS,
                ),
            ),
        )
        assertNull(
            createAttachmentPreviewRouteOrNull(
                localCipherId = "local-cipher",
                remoteCipherId = "remote-cipher",
                attachment = remoteAttachment(fileName = "notes.txt"),
                canPreviewAttachment = CanPreviewAttachmentImpl(
                    platform = Platform.Mobile.Ios,
                ),
            ),
        )
        assertNull(
            createAttachmentPreviewRouteOrNull(
                localCipherId = "local-cipher",
                remoteCipherId = "remote-cipher",
                attachment = remoteAttachment(
                    fileName = "notes.txt",
                    size = AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES + 1,
                ),
                canPreviewAttachment = CanPreviewAttachmentImpl(
                    platform = Platform.Desktop.MacOS,
                ),
            ),
        )
    }

    @Test
    fun `policy classifies preview availability`() {
        val desktopPolicy = CanPreviewAttachmentImpl(platform = Platform.Desktop.MacOS)
        val androidPolicy = CanPreviewAttachmentImpl(
            platform = Platform.Mobile.Android(
                isChromebook = false,
                isWatch = false,
                sdk = 35,
            ),
        )
        val iosPolicy = CanPreviewAttachmentImpl(platform = Platform.Mobile.Ios)

        assertEquals(
            AttachmentPreviewPolicy.Previewable,
            desktopPolicy(
                fileName = "notes.txt",
                encryptedSize = AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES,
            ),
        )
        assertEquals(
            AttachmentPreviewPolicy.Previewable,
            androidPolicy(
                fileName = "photo.png",
                encryptedSize = 1L,
            ),
        )
        assertEquals(
            AttachmentPreviewPolicy.UnsupportedPlatform,
            iosPolicy(
                fileName = "notes.txt",
                encryptedSize = 1L,
            ),
        )
        assertEquals(
            AttachmentPreviewPolicy.UnsupportedType,
            desktopPolicy(
                fileName = "archive.zip",
                encryptedSize = 1L,
            ),
        )

        val tooLarge = assertIs<AttachmentPreviewPolicy.TooLarge>(
            desktopPolicy(
                fileName = "notes.txt",
                encryptedSize = AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES + 1,
            ),
        )
        assertEquals(AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES + 1, tooLarge.actualBytes)
    }

    private fun remoteAttachment(
        fileName: String,
        size: Long = 1L,
    ) = DSecret.Attachment.Remote(
        id = "attachment",
        url = "https://example.com/attachment",
        remoteCipherId = "remote-cipher",
        fileName = fileName,
        keyBase64 = null,
        size = size,
    )

    private fun String.previewLines(): List<String> {
        val lineIndex = AttachmentPreviewLineIndex.of(this)
        return List(lineIndex.size) { index ->
            lineIndex.lineAt(this, index)
        }
    }

    private fun String.previewLineLengths(): List<Int> {
        val lineIndex = AttachmentPreviewLineIndex.of(this)
        return List(lineIndex.size) { index ->
            lineIndex.lineLengthAt(index)
        }
    }

    private fun String.previewMaxLineLength(): Int {
        val lineIndex = AttachmentPreviewLineIndex.of(this)
        return lineIndex.maxLineLength
    }
}
