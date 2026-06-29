package app.keemobile.kotpass.constants

internal object FieldReference {
    enum class WantedField(val key: String) {
        Title("T"),
        UserName("U"),
        Password("P"),
        Url("A"),
        Notes("N"),
        Uuid("I");

        operator fun invoke() = this.key

        companion object {
            operator fun get(key: String) = entries
                .firstOrNull { it.key.equals(key, true) }
        }
    }

    enum class SearchIn(val key: String) {
        Title("T"),
        UserName("U"),
        Password("P"),
        Url("A"),
        Notes("N"),
        Uuid("I"),
        Other("O");

        operator fun invoke() = this.key

        companion object {
            operator fun get(key: String) = entries
                .firstOrNull { it.key.equals(key, true) }
        }
    }
}
