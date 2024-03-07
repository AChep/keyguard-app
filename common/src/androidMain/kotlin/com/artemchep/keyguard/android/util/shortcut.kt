package com.artemchep.keyguard.android.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import com.artemchep.keyguard.android.MainActivity
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.model.DCipherFilter

object ShortcutIds {
    fun forFilter(filterId: String): String {
        return "filter=$filterId"
    }
}

object ShortcutInfo {
    fun forFilter(
        context: Context,
        filter: DCipherFilter,
    ): ShortcutInfoCompat {
        val id = ShortcutIds.forFilter(filter.id)
        val intent = MainActivity.getIntent(context).apply {
            action = Intent.ACTION_VIEW
            putExtra("customFilter", filter.id)
        }
        val icon = IconCompat.createWithResource(context, R.drawable.ic_shortcut_keyguard)
        return ShortcutInfoCompat.Builder(context, id)
            .setIcon(icon)
            .setShortLabel(filter.name)
            .setIntent(intent)
            .addCapabilityBinding("actions.intent.OPEN_APP_FEATURE")
            .build()
    }
}
