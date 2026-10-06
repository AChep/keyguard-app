package com.artemchep.keyguard.common.service.s3

import com.artemchep.keyguard.common.model.S3AccessKey
import com.artemchep.keyguard.common.model.S3Bucket
import com.artemchep.keyguard.common.model.S3Location
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.util.s3.S3ClientConfig
import com.artemchep.keyguard.util.s3.S3Credentials

fun S3Location.toS3ClientConfig(): S3ClientConfig = s3ClientConfigOf(
    bucket = bucket,
    accessKey = accessKey,
)

fun s3ClientConfigOf(
    bucket: S3Bucket,
    accessKey: S3AccessKey,
): S3ClientConfig = S3ClientConfig(
    endpoint = bucket.endpoint,
    region = bucket.region,
    bucket = bucket.name,
    credentials = S3Credentials(
        accessKeyId = accessKey.accessKeyId,
        secretAccessKey = accessKey.secretAccessKey.value,
    ),
    pathStyle = bucket.pathStyle,
)

fun FileLocation.S3.toS3Location(): S3Location.Object = S3Location.Object(
    bucket = S3Bucket(
        endpoint = endpoint,
        region = region,
        name = bucket,
        pathStyle = pathStyle,
    ),
    accessKey = S3AccessKey(
        accessKeyId = accessKeyId,
        secretAccessKey = secretAccessKey,
    ),
    key = key,
)

fun S3Location.Object.toFileLocation(
    displayName: String,
): FileLocation.S3 = FileLocation.S3(
    endpoint = bucket.endpoint?.takeIf { it.isNotBlank() },
    region = bucket.region?.takeIf { it.isNotBlank() },
    bucket = bucket.name,
    key = key,
    accessKeyId = accessKey.accessKeyId,
    secretAccessKey = accessKey.secretAccessKey,
    pathStyle = bucket.pathStyle,
    displayName = displayName,
)

/** Returns the backup destination as a location, or null if it is incomplete. */
fun BackupStoreConfig.S3.toS3LocationOrNull(): S3Location.Prefix? {
    val bucket = bucket?.takeIf { it.isNotBlank() }
    val accessKey = accessKeyId?.takeIf { it.isNotBlank() }?.let { id ->
        secretAccessKey
            ?.takeIf { it.value.isNotEmpty() }
            ?.let { secret -> S3AccessKey(accessKeyId = id, secretAccessKey = secret) }
    }
    val prefix = normalizeS3Prefix(prefix).takeIf(::isValidS3Prefix)
    if (bucket == null || accessKey == null || prefix == null) {
        return null
    }
    return S3Location.Prefix(
        bucket = S3Bucket(
            endpoint = endpoint,
            region = region,
            name = bucket,
            pathStyle = pathStyle,
        ),
        accessKey = accessKey,
        prefix = prefix,
    )
}

fun S3Location.Prefix.toBackupStoreConfig(): BackupStoreConfig.S3 = BackupStoreConfig.S3(
    endpoint = bucket.endpoint?.takeIf { it.isNotBlank() },
    region = bucket.region?.takeIf { it.isNotBlank() },
    bucket = bucket.name,
    prefix = prefix.takeIf { it.isNotEmpty() },
    accessKeyId = accessKey.accessKeyId,
    secretAccessKey = accessKey.secretAccessKey,
    pathStyle = bucket.pathStyle,
)
