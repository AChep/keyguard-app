package com.artemchep.keyguard.android.shortcut

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.artemchep.keyguard.android.MainActivity

class ShortcutTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (isSecure) {
            // Even tho the keyguard is gonna ask for a password, it's better
            // if we force the device to be unlocked first.
            unlockAndRun {
                startMainActivity()
            }
        } else {
            startMainActivity()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val flags =
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(
                this,
                13213,
                intent,
                flags,
            )
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
