package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.provider.keyguard.session.KeyguardSessionLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Holds the session with the linked apps on the other devices while the app is in the
 * foreground and the vault is unlocked. An app left alone keeps notifying removed peers;
 * a session without peers opens no rooms. It starts over when the membership changes.
 */
class AppLinkWorker(
    private val appLinkLauncher: AppLinkLauncher,
    private val sessionLauncher: KeyguardSessionLauncher,
) {
    companion object {
        private const val GROUP_ID = P2pGroupId.APP_LINK_ID
    }

    fun launch(scope: CoroutineScope): Job = scope.launch {
        appLinkLauncher
            .live()
            .map { it?.membership }
            .distinctUntilChanged()
            .collectLatest { membership ->
                membership ?: return@collectLatest
                coroutineScope {
                    appLinkLauncher.queueSync()
                        .launchIn(this)
                    sessionLauncher.hold(GROUP_ID, membership)
                }
            }
    }
}
