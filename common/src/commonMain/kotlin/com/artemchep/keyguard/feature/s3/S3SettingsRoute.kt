package com.artemchep.keyguard.feature.s3

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.S3Location
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

data class S3SettingsRoute(
    val args: Args = Args(),
) : RouteForResult<S3SettingsResult> {
    data class Args(
        val endpoint: String = "",
        val region: String = "",
        val bucket: String = "",
        /** The folder prefix, or the object key of a KeePass database. */
        val path: String = "",
        val accessKeyId: String = "",
        val secretAccessKey: String = "",
        val pathStyle: Boolean = true,
        val purpose: Purpose = Purpose.Prefix,
        val keePassMode: KeePassMode = KeePassMode.Open,
    )

    enum class Purpose {
        Prefix,
        KeePassDatabase,
    }

    enum class KeePassMode {
        Open,
        Create,
    }

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<S3SettingsResult>,
    ) {
        S3SettingsScreen(
            route = this,
            transmitter = transmitter,
        )
    }
}

data class S3SettingsResult(
    val location: S3Location,
)
