package com.artemchep.keyguard.common.model

/**
 * Removes the words [remove] from the wordlist [wordlistId], then adds the
 * words [add]. A word that is both removed and added stays.
 */
data class EditWordlistWordsRequest(
    val wordlistId: Long,
    val remove: Set<String> = emptySet(),
    val add: List<String> = emptyList(),
)
