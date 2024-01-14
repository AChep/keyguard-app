package com.artemchep.keyguard.common.model

data class AddWordlistRequest(
    val name: String,
    val wordlist: Wordlist,
) {
    sealed interface Wordlist {
        data class FromFile(
            val uri: String,
        ) : Wordlist

        data class FromList(
            val list: List<String>,
        ) : Wordlist
    }
}
