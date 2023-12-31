package com.artemchep.keyguard.android.clipboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.artemchep.keyguard.android.Notifications
import com.artemchep.keyguard.android.downloader.receiver.CopyActionReceiver
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.GetClipboardAutoRefresh
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.ui.totp.formatCodeStr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.parcelize.Parcelize
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

class KeyguardClipboardService : Service(), DIAware {
    companion object {
        private const val KEY_ARGUMENTS = "arguments"

        fun getIntent(
            context: Context,
            cipherName: String?,
            totpToken: TotpToken,
        ) = kotlin.run {
            val args = Args.CopyTotpCode(
                cipherName = cipherName,
                token = totpToken.raw,
            )
            getIntent(context, args)
        }

        fun getIntent(
            context: Context,
            cipherName: String?,
            value: String,
            concealed: Boolean,
        ) = kotlin.run {
            val args = Args.CopyValue(cipherName, value, concealed)
            getIntent(context, args)
        }

        fun getIntent(
            context: Context,
            args: Args,
        ) = Intent(context, KeyguardClipboardService::class.java).apply {
            putExtra(KEY_ARGUMENTS, args)
        }
    }

    sealed interface Args : Parcelable {
        @Parcelize
        data class CopyValue(
            val cipherName: String?,
            val value: String,
            val concealed: Boolean,
        ) : Args

        @Parcelize
        data class CopyTotpCode(
            val cipherName: String?,
            val token: String,
        ) : Args

        @Parcelize
        object Cancel : Args
    }

    private data class CopyValueEvent(
        val type: Type,
        val cipherName: String?,
        val value: String,
        val text: String = value,
        val concealed: Boolean,
        val expiration: Instant? = null,
    ) {
        enum class Type {
            VALUE,
            TOTP,
        }
    }

    private val scope: CoroutineScope = object : CoroutineScope {
        override val coroutineContext: CoroutineContext = Dispatchers.Main + Job()
    }

    override val di by closestDI { this }

    private val clipboardService: ClipboardService by instance()

    private val clipboardAutoRefreshFlow by lazy {
        val getClipboardAutoRefresh: GetClipboardAutoRefresh by instance()
        getClipboardAutoRefresh()
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)
    }

    private val notificationManager by lazy {
        getSystemService<NotificationManager>()!!
    }

    private val notificationIdPool = Notifications.totp

    private var notificationId: Int = 0

    override fun onCreate() {
        super.onCreate()
        notificationId = notificationIdPool.obtainId()
    }

    private val actor = scope.actor<Args>(capacity = 16) {
        var previousJob: Job? = null
        consumeEach {
            if (it is Args.Cancel) {
                stopSelf()
                return@consumeEach
            }

            val flow = execute(it)
            if (flow != null) {
                previousJob?.cancel()
                previousJob = executeFlow2(flow)
                    .launchIn(scope)
            }
        }
    }

    private fun execute(args: Args) = when (args) {
        is Args.CopyValue -> execute(args)
        is Args.CopyTotpCode -> execute(args)
        is Args.Cancel -> null
    }

    private fun execute(args: Args.CopyValue): Flow<CopyValueEvent> {
        val event = CopyValueEvent(
            type = CopyValueEvent.Type.VALUE,
            cipherName = args.cipherName,
            value = args.value,
            concealed = args.concealed,
        )
        return flowOf(event)
    }

    private fun execute(args: Args.CopyTotpCode): Flow<CopyValueEvent>? {
        val token = TotpToken.parse(args.token).getOrNull()
        // The token should always be valid, because we
        // verified it before creating a service.
            ?: return null
        val getTotpCode: GetTotpCode by instance()
        return getTotpCode(token)
            .map {
                val expiration = when (val counter = it.counter) {
                    is TotpCode.TimeBasedCounter -> counter.expiration
                    is TotpCode.IncrementBasedCounter -> null
                }
                CopyValueEvent(
                    type = CopyValueEvent.Type.TOTP,
                    cipherName = args.cipherName,
                    value = it.code,
                    text = it.formatCodeStr(),
                    concealed = false,
                    expiration = expiration,
                )
            }
    }

    private fun executeFlow2(flow: Flow<CopyValueEvent>): IO<Any?> = ioEffect {
        val autoRefreshDuration = clipboardAutoRefreshFlow.first()

        flow
            .run {
                @Suppress("UnnecessaryVariable")
                val d = autoRefreshDuration
                if (d.isPositive()) {
                    takeFor(duration = d)
                } else {
                    take(1)
                }
            }
            .mapLatest { event ->
                performCopyToClipboard(event)
                notify(
                    notificationId = notificationId,
                    event = event,
                )

                event
            }
            .lastOrNull()

        yield()
        stopSelf()
    }

    private suspend fun notify(
        notificationId: Int,
        event: CopyValueEvent,
    ) {
        val expiration = event.expiration
        if (expiration != null) {
            var lastSeconds = 0L
            var alertOnlyOnce = false
            do {
                val now = Clock.System.now()
                val newSeconds = (expiration - now).inWholeSeconds
                if (newSeconds != lastSeconds && newSeconds != 0L) {
                    val notification = createNotification(
                        type = event.type,
                        name = event.cipherName,
                        text = event.text,
                        value = event.value,
                        expiration = expiration - now,
                        alertOnlyOnce = alertOnlyOnce,
                    )
                    startForeground(notificationId, notification)
                }

                lastSeconds = newSeconds
                alertOnlyOnce = true // do not notify about status updates
                delay(500L)
            } while (lastSeconds > 0)
        } else {
            val notification = createNotification(
                type = event.type,
                name = event.cipherName,
                text = event.text,
                value = event.value,
                expiration = null,
                alertOnlyOnce = false,
            )
            startForeground(notificationId, notification)
        }
    }

    private fun <T> Flow<T>.takeFor(
        duration: Duration,
    ): Flow<T> = channelFlow<T> {
        coroutineScope {
            val consumeJob = launch {
                onEach {
                    send(it)
                }.collect()
            }

            try {
                withTimeout(duration) {
                    consumeJob.join()
                }
            } catch (e: TimeoutCancellationException) {
                Log.e("totp", "cancel event")
                // Stop observing for more events.
                consumeJob.cancelAndJoin()
            }
        }
    }

    private fun performCopyToClipboard(event: CopyValueEvent) =
        clipboardService.setPrimaryClip(
            value = event.value,
            concealed = event.concealed,
        )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val args = intent?.extras?.getParcelable<Args>(KEY_ARGUMENTS)
        if (args != null) {
            actor.trySend(args)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val notificationId = notificationId
            .also {
                // we can not post with this ID anymore
                notificationId = 0
            }
        if (notificationId != 0) {
            notificationIdPool.releaseId(notificationId)
            notificationManager.cancel(notificationId)
        }

        super.onDestroy()
        scope.cancel()
    }

    //
    // Notifications
    //

    private fun createNotification(
        type: CopyValueEvent.Type,
        name: String?,
        text: String,
        value: String,
        expiration: Duration?,
        alertOnlyOnce: Boolean,
        ongoing: Boolean = true,
    ): Notification {
        val icon = when (expiration?.inWholeSeconds) {
            null -> R.drawable.ic_lock_outline
            0L -> R.drawable.ic_number_0
            1L -> R.drawable.ic_number_1
            2L -> R.drawable.ic_number_2
            3L -> R.drawable.ic_number_3
            4L -> R.drawable.ic_number_4
            5L -> R.drawable.ic_number_5
            6L -> R.drawable.ic_number_6
            7L -> R.drawable.ic_number_7
            8L -> R.drawable.ic_number_8
            9L -> R.drawable.ic_number_9
            else -> R.drawable.ic_number_9_plus
        }

        val contentTitle = when (type) {
            CopyValueEvent.Type.TOTP -> getString(R.string.copied_otp_code)
            CopyValueEvent.Type.VALUE -> getString(R.string.copied_value)
        }
        val contentText = kotlin.run {
            val suffix = expiration
                ?.let { duration ->
                    val seconds = duration.inWholeSeconds
                    " ($seconds)"
                }
                .orEmpty()
            "$text$suffix"
        }

        // Action
        val copyAction = kotlin.run {
            val copyAction = kotlin.run {
                val intent = CopyActionReceiver.cancel(
                    context = applicationContext,
                    value = value,
                )
                PendingIntent.getBroadcast(
                    applicationContext,
                    notificationId,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            val copyTitle = getString(R.string.copy)
            NotificationCompat.Action.Builder(R.drawable.ic_copy, copyTitle, copyAction)
                .build()
        }
        val cancelAction = kotlin.run {
            val cancelAction = kotlin.run {
                val intent = getIntent(
                    context = applicationContext,
                    args = Args.Cancel,
                )
                PendingIntent.getForegroundService(
                    applicationContext,
                    notificationId + 1,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            val cancelTitle = getString(android.R.string.cancel)
            NotificationCompat.Action.Builder(R.drawable.ic_cancel, cancelTitle, cancelAction)
                .build()
        }

        val channelId = createNotificationChannel()
        val audioUri = getAudioUri()
        return NotificationCompat.Builder(this, channelId)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSubText(name)
            .addAction(copyAction)
            .addAction(cancelAction)
            .setSmallIcon(icon)
            .setSound(audioUri)
            .setOnlyAlertOnce(alertOnlyOnce)
            .setOngoing(ongoing)
            .build()
    }

    private fun createNotificationChannel(): String {
        val channelImportance = if (clipboardService.hasCopyNotification()) {
            NotificationManager.IMPORTANCE_DEFAULT
        } else {
            NotificationManager.IMPORTANCE_HIGH
        }
        val channel = kotlin.run {
            val id = getString(R.string.notification_clipboard_channel_id)
            val name = getString(R.string.notification_clipboard_channel_name)
            NotificationChannel(id, name, channelImportance)
        }
        channel.enableVibration(false)
        val audioUri = getAudioUri()
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        channel.setSound(audioUri, audioAttributes)
        val nm = getSystemService<NotificationManager>()!!
        nm.createNotificationChannel(channel)
        return channel.id
    }

    private fun getAudioUri() =
        Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + applicationContext.packageName + "/" + R.raw.silence)
}
