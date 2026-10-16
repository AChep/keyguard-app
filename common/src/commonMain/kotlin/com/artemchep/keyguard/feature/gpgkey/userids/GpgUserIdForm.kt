package com.artemchep.keyguard.feature.gpgkey.userids

/** Structured mode is used only when it round-trips the exact imported packet text. */
internal data class GpgUserIdForm(
    val name: String = "",
    val email: String = "",
    val raw: String = "",
    val exact: Boolean = false,
) {
    val userId: String get() = if (exact) raw else buildGpgUserId(name, email)

    fun toggleFormat(): GpgUserIdForm? =
        if (exact) {
            parse(raw).takeUnless { it.exact }
        } else {
            copy(raw = userId, exact = true)
        }

    companion object {
        private val structured = Regex("^([^<>\\r\\n]+) <([^<>\\s]+)>$")

        fun parse(value: String): GpgUserIdForm {
            val match = structured.matchEntire(value)
            val form =
                if (match != null) {
                    GpgUserIdForm(name = match.groupValues[1], email = match.groupValues[2])
                } else {
                    GpgUserIdForm(name = value)
                }
            val structuredRoundTrip =
                form.userId == value &&
                    (value.none { it in "<>()" } || match != null && form.name.none { it in "()" })
            return if (structuredRoundTrip) form else GpgUserIdForm(raw = value, exact = true)
        }
    }
}

/** Formats the conventional `Name <email>` User ID, omitting a blank email. */
internal fun buildGpgUserId(
    name: String,
    email: String,
): String {
    val nameNormalized = name.trim()
    val emailNormalized = email.trim()
    return if (emailNormalized.isNotEmpty()) {
        "$nameNormalized <$emailNormalized>"
    } else {
        nameNormalized
    }
}
