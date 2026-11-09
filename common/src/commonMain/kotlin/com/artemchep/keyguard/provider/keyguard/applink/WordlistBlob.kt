package com.artemchep.keyguard.provider.keyguard.applink

/**
 * The blob of the words of a wordlist: each word once, sorted by [String] order, one per
 * line, in UTF-8. The order is the one of Kotlin on every platform, not the one of the
 * database, so every device that holds the words builds the same bytes.
 *
 * A blob holds at most [MAX_WORDS] words, each one a row of the database: a list of more
 * stays on its device, as one larger than [AppLinkBlobs.MAX_BLOB_SIZE] does.
 */
internal object WordlistBlob {
    /** The longest word, as the import allows. */
    const val MAX_WORD_LENGTH: Int = 512

    /** The most words of a blob, 2^20: the words of a list as large as the largest blob. */
    const val MAX_WORDS: Int = 1 shl 20

    private const val SEPARATOR = '\n'

    /** Returns the blob of the [words]: each word once, sorted. */
    fun encode(words: Collection<String>): ByteArray = words
        .distinct()
        .sorted()
        .joinToString(separator = SEPARATOR.toString())
        .encodeToByteArray()

    /** Returns how many words the blob [bytes] holds, if it is one; counted on the bytes, before a word is made. */
    fun countOf(bytes: ByteArray): Int = if (bytes.isEmpty()) 0 else bytes.count { it == SEPARATOR.code.toByte() } + 1

    /**
     * Returns the words of the blob [bytes], or `null` if the bytes are not the blob of
     * any words: they are too many, not UTF-8, not in order, hold a word twice, or hold a
     * word that is blank, too long, or holds a line break.
     */
    fun decodeOrNull(bytes: ByteArray): List<String>? = when {
        bytes.size > AppLinkBlobs.MAX_BLOB_SIZE -> null
        bytes.isEmpty() -> emptyList()
        // Counted on the bytes, before a single word is made.
        bytes.count { it == SEPARATOR.code.toByte() } >= MAX_WORDS -> null
        else -> decodeWordsOrNull(bytes)
    }

    private fun decodeWordsOrNull(bytes: ByteArray): List<String>? {
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (expected: CharacterCodingException) {
            null
        }
        return text
            ?.split(SEPARATOR)
            ?.takeIf(::isCanonical)
    }

    /** `true` if each word is valid and comes after the one before it: in order, and once. */
    private fun isCanonical(words: List<String>): Boolean {
        // One pass, nothing sorted.
        var previous: String? = null
        return words.all { word ->
            val valid = word.isNotBlank() &&
                word.length <= MAX_WORD_LENGTH &&
                '\r' !in word &&
                previous.let { before -> before == null || before < word }
            previous = word
            valid
        }
    }
}
