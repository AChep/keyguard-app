package com.artemchep.keyguard.feature.masterkdf

import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.platform.Platform
import kotlin.time.Duration

private const val MILLIS_PER_TENTH = 100L
private const val TENTHS_PER_SECOND = 10L

/**
 * The memory above which the iOS AutoFill extension may run out of
 * memory: its budget is much smaller than the one of the app.
 */
internal const val MASTER_KDF_IOS_AUTOFILL_MEMORY_MIB_MAX = 64

/**
 * A field of the key derivation form, with the bounds the
 * form accepts.
 */
enum class MasterKdfFormField(
    val min: Int,
    val max: Int,
) {
    ITERATIONS(
        min = MasterKdf.Argon2id.ITERATIONS_MIN,
        max = MasterKdf.Argon2id.ITERATIONS_MAX,
    ),
    MEMORY_MIB(
        min = MasterKdf.Argon2id.MEMORY_MIB_MIN,
        max = MasterKdf.Argon2id.MEMORY_MIB_MAX,
    ),
    PARALLELISM(
        min = MasterKdf.Argon2id.PARALLELISM_MIN,
        max = MasterKdf.Argon2id.PARALLELISM_MAX,
    ),
}

sealed interface MasterKdfFieldValidation {
    data class Valid(
        val value: Int,
    ) : MasterKdfFieldValidation

    data object Empty : MasterKdfFieldValidation

    data object NotANumber : MasterKdfFieldValidation

    data object OutOfRange : MasterKdfFieldValidation
}

fun validateMasterKdfField(
    field: MasterKdfFormField,
    text: String,
): MasterKdfFieldValidation {
    val trimmed = text.trim()
    val value = trimmed.toIntOrNull()
    return when {
        trimmed.isEmpty() -> MasterKdfFieldValidation.Empty
        // Too large to fit into an integer.
        value == null && trimmed.all { it.isDigit() } -> MasterKdfFieldValidation.OutOfRange
        value == null -> MasterKdfFieldValidation.NotANumber
        value !in field.min..field.max -> MasterKdfFieldValidation.OutOfRange
        else -> MasterKdfFieldValidation.Valid(value)
    }
}

/**
 * Returns the value of the field, or `null` if the
 * text is not valid.
 */
fun masterKdfFieldValueOrNull(
    field: MasterKdfFormField,
    text: String,
): Int? = (validateMasterKdfField(field, text) as? MasterKdfFieldValidation.Valid)?.value

/**
 * Returns the key derivation of the given form texts, or `null` if
 * any of them is not valid. The memory is in MiB.
 */
fun masterKdfOrNull(
    iterations: String,
    memoryMib: String,
    parallelism: String,
): MasterKdf.Argon2id? = masterKdfOrNull(
    iterations = masterKdfFieldValueOrNull(MasterKdfFormField.ITERATIONS, iterations),
    memoryMib = masterKdfFieldValueOrNull(MasterKdfFormField.MEMORY_MIB, memoryMib),
    parallelism = masterKdfFieldValueOrNull(MasterKdfFormField.PARALLELISM, parallelism),
)

/**
 * Returns the key derivation of the parsed form values, or `null`
 * if any of them is missing. The memory is in MiB.
 */
internal fun masterKdfOrNull(
    iterations: Int?,
    memoryMib: Int?,
    parallelism: Int?,
): MasterKdf.Argon2id? = if (iterations != null && memoryMib != null && parallelism != null) {
    MasterKdf.Argon2id(
        iterations = iterations,
        memoryKib = memoryMib * MasterKdf.Argon2id.KIB_PER_MIB,
        parallelism = parallelism,
    )
} else {
    null
}

/** The text of the iterations field. */
fun MasterKdf.Argon2id.iterationsText(): String = iterations.toString()

/** The text of the memory field, in MiB. */
fun MasterKdf.Argon2id.memoryMibText(): String = (memoryKib / MasterKdf.Argon2id.KIB_PER_MIB).toString()

/** The text of the parallelism field. */
fun MasterKdf.Argon2id.parallelismText(): String = parallelism.toString()

/**
 * Returns `true` if the form should warn that the iOS AutoFill
 * extension may fail to unlock the vault with the given memory.
 */
fun hasMasterKdfMemoryWarning(
    platform: Platform,
    memoryMib: Int?,
): Boolean = platform is Platform.Mobile.Ios &&
        memoryMib != null &&
        memoryMib > MASTER_KDF_IOS_AUTOFILL_MEMORY_MIB_MAX

/**
 * Formats the duration as seconds with one decimal, for
 * example `1.2`. Never shows less than `0.1`.
 */
fun formatMasterKdfSeconds(duration: Duration): String {
    // Round to the closest tenth.
    val tenths = ((duration.inWholeMilliseconds + MILLIS_PER_TENTH / 2) / MILLIS_PER_TENTH)
        .coerceAtLeast(1L)
    return "${tenths / TENTHS_PER_SECOND}.${tenths % TENTHS_PER_SECOND}"
}
