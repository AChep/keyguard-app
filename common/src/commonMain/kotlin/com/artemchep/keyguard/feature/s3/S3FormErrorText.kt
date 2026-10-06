package com.artemchep.keyguard.feature.s3

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.remotepicker.KEEPASS_DATABASE_EXTENSION
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_s3_access_key_id_invalid
import com.artemchep.keyguard.res.error_s3_access_key_id_required
import com.artemchep.keyguard.res.error_s3_bucket_invalid
import com.artemchep.keyguard.res.error_s3_bucket_required
import com.artemchep.keyguard.res.error_s3_endpoint_invalid
import com.artemchep.keyguard.res.error_s3_key_extension
import com.artemchep.keyguard.res.error_s3_key_invalid
import com.artemchep.keyguard.res.error_s3_key_required
import com.artemchep.keyguard.res.error_s3_prefix_invalid
import com.artemchep.keyguard.res.error_s3_secret_access_key_required
import org.jetbrains.compose.resources.stringResource

@Composable
fun s3FormErrorText(
    error: S3FormError,
): String = when (error) {
    S3FormError.EndpointInvalid -> stringResource(Res.string.error_s3_endpoint_invalid)
    S3FormError.BucketRequired -> stringResource(Res.string.error_s3_bucket_required)
    S3FormError.BucketInvalid -> stringResource(Res.string.error_s3_bucket_invalid)
    S3FormError.PrefixInvalid -> stringResource(Res.string.error_s3_prefix_invalid)
    S3FormError.KeyRequired -> stringResource(Res.string.error_s3_key_required)
    S3FormError.KeyInvalid -> stringResource(Res.string.error_s3_key_invalid)
    S3FormError.KeyExtensionRequired -> stringResource(
        Res.string.error_s3_key_extension,
        KEEPASS_DATABASE_EXTENSION,
    )
    S3FormError.AccessKeyIdRequired -> stringResource(Res.string.error_s3_access_key_id_required)
    S3FormError.AccessKeyIdInvalid -> stringResource(Res.string.error_s3_access_key_id_invalid)
    S3FormError.SecretAccessKeyRequired -> stringResource(Res.string.error_s3_secret_access_key_required)
}
