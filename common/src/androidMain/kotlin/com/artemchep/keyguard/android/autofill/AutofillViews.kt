package com.artemchep.keyguard.android.autofill

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.artemchep.keyguard.common.R

object AutofillViews {
    fun buildPopupEntry(
        context: Context,
        title: String,
        text: String? = null,
    ) = RemoteViews(context.packageName, R.layout.item_autofill_entry).apply {
        setTextViewText(R.id.autofill_entry_name, title)
        if (text.isNullOrEmpty()) {
            setViewVisibility(R.id.autofill_entry_username, View.GONE)
        } else {
            setTextViewText(R.id.autofill_entry_username, text)
        }
    }

    fun buildPopupEntryManual(
        context: Context,
    ) = RemoteViews(context.packageName, R.layout.item_autofill_select_entry)

    fun buildPopupKeyguardUnlock(
        context: Context,
        webDomain: String? = null,
        appId: String? = null,
    ) = kotlin.run {
        if (!webDomain.isNullOrEmpty()) {
            RemoteViews(
                context.packageName,
                R.layout.item_autofill_unlock_web_domain,
            ).apply {
                setTextViewText(
                    R.id.autofill_web_domain_text,
                    webDomain,
                )
            }
        } else if (!appId.isNullOrEmpty()) {
            RemoteViews(context.packageName, R.layout.item_autofill_unlock_app_id).apply {
                setTextViewText(
                    R.id.autofill_app_id_text,
                    appId,
                )
            }
        } else {
            RemoteViews(context.packageName, R.layout.item_autofill_unlock)
        }
    }

    fun buildPopupKeyguardOpen(
        context: Context,
        webDomain: String? = null,
        appId: String? = null,
    ) = kotlin.run {
        if (!webDomain.isNullOrEmpty()) {
            RemoteViews(
                context.packageName,
                R.layout.item_autofill_select_entry_web_domain,
            ).apply {
                setTextViewText(
                    R.id.autofill_web_domain_text,
                    webDomain,
                )
            }
        } else if (!appId.isNullOrEmpty()) {
            RemoteViews(context.packageName, R.layout.item_autofill_select_entry_app_id).apply {
                setTextViewText(
                    R.id.autofill_app_id_text,
                    appId,
                )
            }
        } else {
            RemoteViews(context.packageName, R.layout.item_autofill_select_entry)
        }
    }
}
