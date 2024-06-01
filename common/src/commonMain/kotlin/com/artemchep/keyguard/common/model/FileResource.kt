package com.artemchep.keyguard.common.model

@JvmInline
value class FileResource(
    val name: String,
) {
    companion object {
        val gpmPasskeysPrivilegedApps: FileResource
            get() = "files/gpm_passkeys_privileged_apps.json"
                .let(::FileResource)

        val justDeleteMe: FileResource
            get() = "files/justdeleteme.json"
                .let(::FileResource)

        val justGetMyData: FileResource
            get() = "files/justgetmydata.json"
                .let(::FileResource)

        val licenses: FileResource
            get() = "files/licenses.json"
                .let(::FileResource)

        val passkeys: FileResource
            get() = "files/passkeys.json"
                .let(::FileResource)

        val publicSuffixList: FileResource
            get() = "files/public_suffix_list.txt"
                .let(::FileResource)

        val tfa: FileResource
            get() = "files/tfa.json"
                .let(::FileResource)

        val wordlist: FileResource
            get() = "files/wordlist_en_eff.txt"
                .let(::FileResource)
    }
}
