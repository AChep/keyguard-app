package com.artemchep.keyguard.feature.home.vault.search.engine

enum class SearchTokenizerProfile {
    TEXT,
    URL,
    HOST,
    EMAIL,
    IDENTIFIER,
    SENSITIVE,
}

data class SearchTokenizerConfig(
    val minimumTokenLength: Int = 1,
    val stopWords: Set<String> = emptySet(),
    val dropStopWords: Boolean = true,
) {
    companion object {
        val Default = SearchTokenizerConfig()
    }
}

data class SearchTokenization(
    val normalizedText: String,
    val terms: List<String>,
    val exactNormalizedText: String = normalizedText,
    val exactTerms: List<String> = terms,
)

interface SearchTokenizer {
    fun tokenize(
        value: String,
        profile: SearchTokenizerProfile,
        config: SearchTokenizerConfig = SearchTokenizerConfig.Default,
    ): SearchTokenization

    fun normalize(
        value: String,
        profile: SearchTokenizerProfile,
        config: SearchTokenizerConfig = SearchTokenizerConfig.Default,
    ): String =
        tokenize(
            value = value,
            profile = profile,
            config = config,
        ).normalizedText
}
