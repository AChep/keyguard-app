package com.artemchep.keyguard.feature.environments

/**
 * Suggests a variable name for a referenced field: the name of a custom
 * field, or the item name followed by `_PASSWORD`. Returns an empty string
 * if nothing usable is left, for example for a name without Latin letters.
 */
fun suggestEnvironmentVariableName(
    field: EnvironmentFieldReference,
    itemName: String,
): String {
    val raw = when (field) {
        EnvironmentFieldReference.Password -> "${itemName}_PASSWORD"
        is EnvironmentFieldReference.Custom -> field.name
    }
    val name = raw
        .uppercase()
        .map { char ->
            if (char in 'A'..'Z' || char in '0'..'9' || char == '_') char else '_'
        }
        .joinToString(separator = "")
        .replace(Regex("_+"), "_")
        .trim('_')
    return if (name.firstOrNull()?.isDigit() == true) "_$name" else name
}
