package com.artemchep.keyguard.feature.gpgkey

import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

internal class RecordingGpgTranslator : TranslatorScope {
    val calls = mutableListOf<Pair<StringResource, List<Any>>>()

    override suspend fun translate(res: StringResource): String = translate(res, *emptyArray())

    override suspend fun translate(res: StringResource, vararg args: Any): String {
        calls += res to args.toList()
        return args.joinToString()
    }

    override suspend fun translate(
        res: PluralStringResource,
        quantity: Int,
        vararg args: Any,
    ): String = error("Unexpected plural translation")
}
