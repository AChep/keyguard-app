package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.s3.isValidS3Prefix
import com.artemchep.keyguard.util.s3.isValidS3ObjectKey

data class S3Bucket(
    /** The service endpoint; null or blank means Amazon S3. */
    val endpoint: String?,
    /** The signing region; null or blank means `us-east-1`. */
    val region: String?,
    val name: String,
    val pathStyle: Boolean = true,
) {
    init {
        require(name.isNotBlank()) {
            "S3 bucket name must not be blank."
        }
    }
}

data class S3AccessKey(
    val accessKeyId: String,
    val secretAccessKey: Password,
) {
    init {
        require(accessKeyId.isNotBlank()) {
            "S3 access key ID must not be blank."
        }
        require(secretAccessKey.value.isNotEmpty()) {
            "S3 secret access key must not be empty."
        }
    }
}

sealed interface S3Location {
    val bucket: S3Bucket
    val accessKey: S3AccessKey

    data class Prefix(
        override val bucket: S3Bucket,
        override val accessKey: S3AccessKey,
        /** Empty for the bucket root, otherwise ends with `/`. */
        val prefix: String,
    ) : S3Location {
        init {
            require(isValidS3Prefix(prefix)) {
                "S3 prefix must be empty or end with '/' and contain no empty, '.' or '..' segments."
            }
        }
    }

    data class Object(
        override val bucket: S3Bucket,
        override val accessKey: S3AccessKey,
        val key: String,
    ) : S3Location {
        init {
            require(isValidS3ObjectKey(key)) {
                "S3 object key must not be empty or contain empty, '.' or '..' segments."
            }
        }
    }
}
