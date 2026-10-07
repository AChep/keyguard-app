package com.artemchep.keyguard.feature.s3

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.model.S3AccessKey
import com.artemchep.keyguard.common.model.S3Bucket
import com.artemchep.keyguard.common.model.S3Location
import com.artemchep.keyguard.common.service.s3.isValidS3Prefix
import com.artemchep.keyguard.common.service.s3.normalizeS3Endpoint
import com.artemchep.keyguard.common.service.s3.normalizeS3Prefix
import com.artemchep.keyguard.feature.remotepicker.KEEPASS_DATABASE_EXTENSION
import com.artemchep.keyguard.util.s3.isValidS3AccessKeyId
import com.artemchep.keyguard.util.s3.isValidS3BucketName
import com.artemchep.keyguard.util.s3.isValidS3EndpointUrl
import com.artemchep.keyguard.util.s3.isValidS3ObjectKey

/** Each error belongs to exactly one form field. */
enum class S3FormError(val field: String) {
    EndpointInvalid("endpoint"),
    BucketRequired("bucket"),
    BucketInvalid("bucket"),
    PrefixInvalid("key"),
    KeyRequired("key"),
    KeyInvalid("key"),
    KeyExtensionRequired("key"),
    AccessKeyIdRequired("accessKeyId"),
    AccessKeyIdInvalid("accessKeyId"),
    SecretAccessKeyRequired("secretAccessKey"),
}

data class S3FormInput(
    val endpoint: String,
    val region: String,
    val bucket: String,
    /** The folder prefix, or the object key of a KeePass database. */
    val path: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val pathStyle: Boolean,
)

/**
 * Validates the fields needed to reach the bucket. A saved secret that the
 * form keeps when its secret field is empty satisfies [hasSavedSecret].
 */
fun validateS3Connection(
    input: S3FormInput,
    hasSavedSecret: Boolean = false,
): S3FormError? = s3ConnectionErrors(input, hasSavedSecret).firstOrNull()

internal fun s3ConnectionErrors(
    input: S3FormInput,
    hasSavedSecret: Boolean = false,
): List<S3FormError> {
    val endpoint = input.endpoint.trim()
    val bucket = input.bucket.trim()
    return listOfNotNull(
        S3FormError.EndpointInvalid.takeIf { endpoint.isNotEmpty() && !isValidS3EndpointUrl(endpoint) },
        when {
            bucket.isEmpty() -> S3FormError.BucketRequired
            !isValidS3BucketName(bucket, pathStyle = input.pathStyle) -> S3FormError.BucketInvalid
            else -> null
        },
        when {
            input.accessKeyId.isBlank() -> S3FormError.AccessKeyIdRequired
            !isValidS3AccessKeyId(input.accessKeyId.trim()) -> S3FormError.AccessKeyIdInvalid
            else -> null
        },
        S3FormError.SecretAccessKeyRequired.takeIf { input.secretAccessKey.isEmpty() && !hasSavedSecret },
    )
}

fun validateS3Form(
    input: S3FormInput,
    purpose: S3SettingsRoute.Purpose,
    hasSavedSecret: Boolean = false,
): S3FormError? = s3FormErrors(input, purpose, hasSavedSecret).firstOrNull()

internal fun s3FormErrors(
    input: S3FormInput,
    purpose: S3SettingsRoute.Purpose,
    hasSavedSecret: Boolean = false,
): List<S3FormError> {
    val path = normalizeS3FormPath(input.path, purpose)
    val pathError = when (purpose) {
        S3SettingsRoute.Purpose.Prefix -> S3FormError.PrefixInvalid
            .takeUnless { isValidS3Prefix(path) }

        S3SettingsRoute.Purpose.KeePassDatabase -> when {
            path.isEmpty() -> S3FormError.KeyRequired
            !isValidS3ObjectKey(path) -> S3FormError.KeyInvalid
            !path.endsWith(KEEPASS_DATABASE_EXTENSION, ignoreCase = true) -> S3FormError.KeyExtensionRequired
            else -> null
        }
    }
    return s3ConnectionErrors(input, hasSavedSecret) + listOfNotNull(pathError)
}

/** Normalizes the path field: a `/`-terminated prefix, or an object key without a leading slash. */
fun normalizeS3FormPath(
    path: String,
    purpose: S3SettingsRoute.Purpose,
): String = when (purpose) {
    S3SettingsRoute.Purpose.Prefix -> normalizeS3Prefix(path)
    S3SettingsRoute.Purpose.KeePassDatabase -> path.trim().trimStart('/')
}

/** Builds the location from input that passed [validateS3Form]. */
fun buildS3Location(
    input: S3FormInput,
    purpose: S3SettingsRoute.Purpose,
): S3Location {
    val bucket = input.toS3Bucket()
    val accessKey = input.toS3AccessKey()
    val path = normalizeS3FormPath(input.path, purpose)
    return when (purpose) {
        S3SettingsRoute.Purpose.Prefix -> S3Location.Prefix(
            bucket = bucket,
            accessKey = accessKey,
            prefix = path,
        )

        S3SettingsRoute.Purpose.KeePassDatabase -> S3Location.Object(
            bucket = bucket,
            accessKey = accessKey,
            key = path,
        )
    }
}

internal fun S3FormInput.toS3Bucket() = S3Bucket(
    endpoint = normalizeS3Endpoint(endpoint),
    region = region.trim().takeIf { it.isNotEmpty() },
    name = bucket.trim(),
    pathStyle = pathStyle,
)

internal fun S3FormInput.toS3AccessKey() = S3AccessKey(
    accessKeyId = accessKeyId.trim(),
    secretAccessKey = Password(secretAccessKey),
)
