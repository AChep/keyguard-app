package com.artemchep.keyguard.feature.home.vault.util

import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysResult
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_keyserver_refresh_failed_title
import com.artemchep.keyguard.res.gpg_keyserver_refresh_not_found_title
import com.artemchep.keyguard.res.gpg_keyserver_refresh_skipped_title
import com.artemchep.keyguard.res.gpg_keyserver_refresh_success_title
import org.jetbrains.compose.resources.StringResource

internal data class GpgKeyserverRefreshFeedback(
    val title: StringResource,
    val type: ToastMessage.Type,
)

internal fun RefreshGpgPublicKeysResult.toFeedback(): GpgKeyserverRefreshFeedback = when {
    failed > 0 -> GpgKeyserverRefreshFeedback(
        Res.string.gpg_keyserver_refresh_failed_title,
        ToastMessage.Type.ERROR,
    )
    refreshed > 0 -> GpgKeyserverRefreshFeedback(
        Res.string.gpg_keyserver_refresh_success_title,
        ToastMessage.Type.SUCCESS,
    )
    notFound > 0 -> GpgKeyserverRefreshFeedback(
        Res.string.gpg_keyserver_refresh_not_found_title,
        ToastMessage.Type.INFO,
    )
    else -> GpgKeyserverRefreshFeedback(
        Res.string.gpg_keyserver_refresh_skipped_title,
        ToastMessage.Type.INFO,
    )
}
