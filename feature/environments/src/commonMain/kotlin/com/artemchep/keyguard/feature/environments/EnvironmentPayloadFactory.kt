package com.artemchep.keyguard.feature.environments

/** The longest name of an environment. */
private const val MAX_ENVIRONMENT_NAME_LENGTH = 256

/** Whether an environment can be saved with the [name]. */
fun isValidEnvironmentName(name: String): Boolean =
    name.isNotBlank() && name.length <= MAX_ENVIRONMENT_NAME_LENGTH

/** The [name] cut to the longest name an environment takes. */
fun coerceEnvironmentName(name: String): String = name.take(MAX_ENVIRONMENT_NAME_LENGTH)

/** The payload an environment is saved with, or why it can not be saved. */
sealed interface EnvironmentPayloadResult {
    data class Ok(
        val payload: EnvironmentPayload.Decoded,
    ) : EnvironmentPayloadResult

    /** The definition breaks a limit or a rule of [EnvironmentResolver]. */
    data object Invalid : EnvironmentPayloadResult

    /** The document is larger than [EnvironmentFormat.MAX_PAYLOAD_BYTES]. */
    data object TooLarge : EnvironmentPayloadResult
}

/** The payload an environment with the [definition] is saved with. */
fun environmentPayloadOf(
    definition: EnvironmentDefinition,
): EnvironmentPayloadResult {
    if (!EnvironmentResolver.fitsLimits(definition) || EnvironmentResolver.validate(definition).isNotEmpty()) {
        return EnvironmentPayloadResult.Invalid
    }
    return try {
        val document = EnvironmentFormat.encode(definition)
        EnvironmentPayloadResult.Ok(
            payload = EnvironmentPayload.Decoded(definition, document, lossless = true),
        )
    } catch (_: IllegalArgumentException) {
        // The checks above cover everything but the serialized-size bound.
        EnvironmentPayloadResult.TooLarge
    }
}
