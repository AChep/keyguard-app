package com.artemchep.keyguard.feature.s3

/** Validation feedback is independent of the draft and of keyboard focus. */
data class S3FormValidation(
    val errors: List<S3FormError> = emptyList(),
    val editedFields: Set<String> = emptySet(),
    val validatedFields: Set<String> = emptySet(),
    val request: Int = 0,
    val focusField: String? = null,
) {
    fun edit(field: String, errors: List<S3FormError>): S3FormValidation = copy(
        editedFields = editedFields + field,
        errors = errors.filter { it.field in validatedFields },
    )

    fun blur(field: String, errors: List<S3FormError>): S3FormValidation {
        val fields = validatedFields + setOf(field).filter { it in editedFields }
        return copy(validatedFields = fields, errors = errors.filter { it.field in fields })
    }

    fun submit(errors: List<S3FormError>): S3FormValidation = copy(
        errors = errors,
        validatedFields = setOf("endpoint", "bucket", "key", "accessKeyId", "secretAccessKey"),
        // Repeated invalid submissions must reveal the field again, even when the error is unchanged.
        request = request + 1,
        focusField = listOf("endpoint", "bucket", "key", "accessKeyId", "secretAccessKey")
            .firstOrNull { field -> errors.any { it.field == field } },
    )
}
