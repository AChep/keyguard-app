package com.artemchep.keyguard.test

import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

/**
 * A [TranslatorScope] that returns the resource's `toString()` instead of
 * resolving it. Use it when a test does not assert on translated text.
 */
internal object TestTranslator : TranslatorScope {
    override suspend fun translate(res: StringResource): String = res.toString()

    override suspend fun translate(res: StringResource, vararg args: Any): String =
        res.toString()

    override suspend fun translate(
        res: PluralStringResource,
        quantity: Int,
        vararg args: Any,
    ): String = res.toString()
}

/**
 * A [ClipboardService] that ignores every call. It reports a copy notification,
 * so [CopyText] never translates or shows a message.
 */
internal object NoOpClipboardService : ClipboardService {
    override fun setPrimaryClip(value: String, concealed: Boolean) = Unit

    override fun clearPrimaryClip() = Unit

    override fun hasCopyNotification(): Boolean = true
}

internal fun testCopyText(
    clipboardService: ClipboardService = NoOpClipboardService,
): CopyText = CopyText(
    clipboardService = clipboardService,
    translator = TestTranslator,
    onMessage = {},
)
