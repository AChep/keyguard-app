package com.artemchep.keyguard.feature.s3

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.S3AccessKey
import com.artemchep.keyguard.common.model.S3Bucket
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.remotepicker.RemotePickerMode

data class S3PickerRoute(
    val args: Args,
) : RouteForResult<S3PickerResult> {
    data class Args(
        val bucket: S3Bucket,
        val accessKey: S3AccessKey,
        val mode: RemotePickerMode,
        /** The prefix to start in, without a trailing slash. */
        val initialPath: String = "",
        val initialFileName: String = "",
    )

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<S3PickerResult>,
    ) {
        S3PickerScreen(
            route = this,
            transmitter = transmitter,
        )
    }
}

data class S3PickerResult(
    /** A folder prefix that ends with `/` (or is empty), or an object key. */
    val key: String,
)
