package app.keemobile.kotpass.constants

/**
 * Represents dynamic placeholders used in KeePass.
 */
internal enum class Placeholder(val value: String) {
    /** The entry’s title: `{TITLE}`. */
    Title("TITLE"),

    /** The entry’s user name: `{USERNAME}`. */
    UserName("USERNAME"),

    /** The entry’s password: `{PASSWORD}`. */
    Password("PASSWORD"),

    /** The entry’s URL: `{URL}`. */
    Url("URL"),

    /** The entry’s notes: `{NOTES}`. */
    Notes("NOTES"),

    /** The entry’s unique ID: `{UUID}`. */
    Uuid("UUID"),

    /** Prefix for a field reference to another entry, e.g., `{REF:T@...}`. */
    Reference("REF:"),

    /** Prefix for a custom string field, e.g., `{S:FieldName}`. */
    CustomField("S:");

    operator fun invoke() = this.value
}
