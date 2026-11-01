package com.artemchep.keyguard.feature.home.settings.backups

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.model.S3Location
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.s3.toBackupStoreConfig
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.s3.S3SettingsRoute
import com.artemchep.keyguard.feature.webdav.WebDavSettingsRoute
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform

/**
 * Asks for a backup location of the same kind as [current], starting from its
 * values: a folder through [filePicker], or a WebDAV or S3 location through
 * their settings screens. [receive] gets the chosen location, or `null` when
 * the folder picker was cancelled.
 *
 * [write] asks for write access to a folder, kept across restarts on
 * Android; reading an existing backup needs neither.
 */
internal fun RememberStateFlowScope.pickBackupLocation(
    current: BackupStoreConfig,
    filePicker: EventFlow<FilePickerIntent<*>>,
    write: Boolean,
    receive: (BackupStoreConfig?) -> Unit,
) {
    when (current) {
        is BackupStoreConfig.Local -> filePicker.emit(
            FilePickerIntent.OpenDirectory(
                readUriPermission = true,
                writeUriPermission = write,
                persistableUriPermission = write && CurrentPlatform is Platform.Mobile.Android,
            ) { result ->
                receive(result?.let {
                    BackupStoreConfig.Local(
                        path = it.uri.toString(),
                        accessToken = it.accessToken?.let(::FileAccessToken),
                    )
                })
            },
        )
        is BackupStoreConfig.WebDav -> {
            val route = registerRouteResultReceiver(
                WebDavSettingsRoute(
                    WebDavSettingsRoute.Args(
                        url = current.url.orEmpty(),
                        username = current.username.orEmpty(),
                        password = current.password?.value.orEmpty(),
                    ),
                ),
            ) { result ->
                receive(
                    BackupStoreConfig.WebDav(
                        url = result.url,
                        username = result.username,
                        password = result.password?.takeIf(String::isNotEmpty)?.let(::Password),
                    ),
                )
            }
            navigate(NavigationIntent.NavigateToRoute(route))
        }
        is BackupStoreConfig.S3 -> {
            val route = registerRouteResultReceiver(
                S3SettingsRoute(
                    S3SettingsRoute.Args(
                        endpoint = current.endpoint.orEmpty(),
                        region = current.region.orEmpty(),
                        bucket = current.bucket.orEmpty(),
                        path = current.prefix.orEmpty(),
                        accessKeyId = current.accessKeyId.orEmpty(),
                        secretAccessKey = current.secretAccessKey?.value.orEmpty(),
                        pathStyle = current.pathStyle,
                        purpose = S3SettingsRoute.Purpose.Prefix,
                    ),
                ),
            ) { result ->
                receive((result.location as? S3Location.Prefix)?.toBackupStoreConfig())
            }
            navigate(NavigationIntent.NavigateToRoute(route))
        }
    }
}
