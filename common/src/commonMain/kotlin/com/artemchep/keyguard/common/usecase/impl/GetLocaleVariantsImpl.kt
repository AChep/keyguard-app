package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.usecase.GetLocaleVariants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

class GetLocaleVariantsImpl() : GetLocaleVariants {
    constructor(directDI: DirectDI) : this()

    private val variants = listOf(
        null,
        "af-ZA",
        "ca-ES",
        "de-DE",
        "es-ES",
        "ja-JP",
        "no-NO",
        "pt-PT",
        "sr-SP",
        "uk-UA",
        "zh-TW",
        "ar-SA",
        "cs-CZ",
        "el-GR",
        "fr-FR",
        "it-IT",
        "ko-KR",
        "pl-PL",
        "ro-RO",
        "sv-SE",
        "vi-VN",
        "da-DK",
        "en-US",
        "en-GB",
        "fi-FI",
        "hu-HU",
        "iw-IL",
        "nl-NL",
        "pt-BR",
        "ru-RU",
        "tr-TR",
        "zh-CN",
    )

    override fun invoke(): Flow<List<String?>> = flowOf(variants)
}
