package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.common.model.AttachmentPreviewKind
import com.artemchep.keyguard.common.model.attachmentPreviewFileExtension
import com.artemchep.keyguard.common.model.attachmentPreviewKindByFileName
import dev.snipme.highlights.model.SyntaxLanguage

fun attachmentPreviewSyntaxLanguageByFileName(
    fileName: String,
): SyntaxLanguage? = when (fileName.attachmentPreviewFileExtension()) {
    "c" -> SyntaxLanguage.C
    "h" -> SyntaxLanguage.C
    "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> SyntaxLanguage.CPP
    "cs" -> SyntaxLanguage.CSHARP
    "coffee" -> SyntaxLanguage.COFFEESCRIPT
    "dart" -> SyntaxLanguage.DART
    "go" -> SyntaxLanguage.GO
    "java" -> SyntaxLanguage.JAVA
    "js", "jsx", "mjs", "cjs" -> SyntaxLanguage.JAVASCRIPT
    "kt", "kts" -> SyntaxLanguage.KOTLIN
    "pl", "pm" -> SyntaxLanguage.PERL
    "php" -> SyntaxLanguage.PHP
    "py" -> SyntaxLanguage.PYTHON
    "rb" -> SyntaxLanguage.RUBY
    "rs" -> SyntaxLanguage.RUST
    "sh", "bash", "zsh", "fish", "command", "bat", "ps1" -> SyntaxLanguage.SHELL
    "swift" -> SyntaxLanguage.SWIFT
    "ts", "tsx", "mts", "cts" -> SyntaxLanguage.TYPESCRIPT
    else -> when (attachmentPreviewKindByFileName(fileName)) {
        AttachmentPreviewKind.Markdown,
        AttachmentPreviewKind.Text -> SyntaxLanguage.DEFAULT

        AttachmentPreviewKind.Image,
        null -> null
    }
}
