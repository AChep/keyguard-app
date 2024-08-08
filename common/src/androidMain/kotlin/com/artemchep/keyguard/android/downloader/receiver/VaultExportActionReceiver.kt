package com.artemchep.keyguard.android.downloader.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import org.kodein.di.android.closestDI
import org.kodein.di.direct
import org.kodein.di.instance

class VaultExportActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_VAULT_EXPORT_CANCEL = ".ACTION_VAULT_EXPORT_CANCEL"

        const val KEY_EXPORT_ID = "export_id"

        fun cancel(
            context: Context,
            exportId: String,
        ): Intent = intent(
            context = context,
            suffix = ACTION_VAULT_EXPORT_CANCEL,
        ) {
            putExtra(KEY_EXPORT_ID, exportId)
        }

        fun intent(
            context: Context,
            suffix: String,
            builder: Intent.() -> Unit = {},
        ): Intent {
            val action = kotlin.run {
                val packageName = context.packageName
                "$packageName$suffix"
            }
            return Intent(action).apply {
                component = ComponentName(context, VaultExportActionReceiver::class.java)
                builder()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
            ?: return
        val di by closestDI { context }
        when {
            action.endsWith(ACTION_VAULT_EXPORT_CANCEL) -> {
                val exportId = intent.extras?.getString(KEY_EXPORT_ID)
                    ?: return
                val windowCoroutineScope: WindowCoroutineScope by di.instance()

                // Try to get the export manager from
                // a current session.
                val exportManager: ExportManager = kotlin.run {
                    val getSession: GetVaultSession = di.direct.instance()
                    val s = getSession.valueOrNull as? MasterSession.Key
                        ?: return@run null
                    s.di.direct.instance()
                } ?: return
                exportManager.cancel(exportId)
            }
        }
    }
}
