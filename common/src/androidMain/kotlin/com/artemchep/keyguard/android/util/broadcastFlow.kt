package com.artemchep.keyguard.android.util

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

@SuppressLint("UnspecifiedRegisterReceiverFlag")
fun broadcastFlow(
    context: Context,
    intentFilter: IntentFilter,
    /**
     * If `false`, only the app itself or the system can send
     * those broadcast events.
     */
    exported: Boolean = false,
): Flow<Intent> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(intent)
        }
    }
    try {
        if (Build.VERSION.SDK_INT >= 34) {
            val flag = if (exported) {
                Context.RECEIVER_EXPORTED
            } else {
                Context.RECEIVER_NOT_EXPORTED
            }
            context.registerReceiver(receiver, intentFilter, flag)
        } else {
            context.registerReceiver(receiver, intentFilter)
        }
        awaitClose()
    } finally {
        withContext(
            context = NonCancellable + Dispatchers.Main,
        ) {
            context.unregisterReceiver(receiver)
        }
    }
}.flowOn(Dispatchers.Main)
