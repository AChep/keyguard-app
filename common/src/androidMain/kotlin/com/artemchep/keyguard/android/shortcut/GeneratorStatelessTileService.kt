package com.artemchep.keyguard.android.shortcut

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.artemchep.keyguard.android.GeneratorActivity

class GeneratorStatelessTileService : TileService() {
    override fun onClick() {
        super.onClick()
        startGeneratorActivity()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startGeneratorActivity() {
        val intent = Intent(this, GeneratorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val flags =
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(
                this,
                13215,
                intent,
                flags,
            )
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
