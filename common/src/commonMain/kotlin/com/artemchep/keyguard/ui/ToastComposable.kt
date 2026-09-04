package com.artemchep.keyguard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.MessageHub
import com.artemchep.keyguard.feature.navigation.navigationNodeStack
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LocalWindowId
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.theme.okContainer
import com.artemchep.keyguard.ui.theme.onOkContainer
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kodein.di.compose.rememberInstance

@Composable
fun ToastMessageHost(
    modifier: Modifier = Modifier,
    itemEnterTransition: EnterTransition = fadeIn() +
            scaleIn() +
            expandIn(initialSize = { IntSize(it.width, 0) }),
    itemExitTransition: ExitTransition = fadeOut() +
            scaleOut() +
            shrinkOut(targetSize = { IntSize(it.width, 0) }),
    item: @Composable (Modifier, ToastMessage) -> Unit = { modifier, msg ->
        ToastMessage(
            modifier = modifier
                .clip(MaterialTheme.shapes.extraLarge),
            model = msg,
        )
    },
    content: @Composable (Modifier, List<ToastMessageRenderItem>) -> Unit = { modifier, items ->
        Column(
            modifier = modifier
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    bottom = 48.dp,
                )
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items.forEach { item ->
                key(item.drawable.key) {
                    item.drawable.Content()
                }
            }
        }
    },
) {
    val messagesState = remember {
        val initialState = listOf<ToastMessageRenderItem>()
        MutableStateFlow(initialState)
    }

    val hub by rememberInstance<MessageHub>()
    val nav = navigationNodeStack()
    val scope = rememberCoroutineScope()

    val windowIsFocused = LocalWindowInfo.current.isWindowFocused
    val windowId = LocalWindowId.current
    DisposableEffect(hub, scope, windowId, windowIsFocused) {
        // If the window is not focused then we do not want it
        // to intercept the toast messages.
        if (!windowIsFocused) {
            return@DisposableEffect onDispose {
                // Do nothing
            }
        }

        val unregister = hub.register(key = nav, windowId = windowId) { message ->
            messagesState.update { existingMessages ->
                val out = existingMessages.toMutableList()
                val index =
                    out.indexOfFirst { it.cancellationJob.isActive && it.drawable.key == message.id }
                if (index != -1) {
                    // Stop cancellation job as it will be replaced
                    out[index].cancellationJob.cancel()
                }

                val cancellationJob = scope.launch(Dispatchers.Main) {
                    val duration = message.duration
                    delay(duration)
                    // Delete a message
                    messagesState.value.forEach { item ->
                        if (item.drawable.key != message.id) {
                            return@forEach
                        }

                        item.drawable.dispose()
                    }
                }
                val model = ToastMessageRenderItem(
                    drawable = ToastMessageRenderDrawable(
                        key = message.id,
                        enterTransition = itemEnterTransition,
                        exitTransition = itemExitTransition,
                        content = {
                            item(
                                Modifier,
                                message,
                            )
                        },
                        onDisposed = {
                            messagesState.update { l ->
                                l.toMutableList()
                                    .apply { removeAll { it.drawable.key == message.id } }
                            }
                        },
                    ),
                    cancellationJob = cancellationJob,
                )
                // Insert the message into a global list
                if (index != -1) {
                    out[index] = model
                } else {
                    out.add(0, model)
                }
                out
            }
        }
        onDispose {
            unregister.invoke()
        }
    }

    val messages by messagesState.collectAsState()
    content(
        modifier,
        messages,
    )
}

@Composable
fun ToastMessage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    model: ToastMessage,
) {
    val containerColor = when (model.type) {
        ToastMessage.Type.ERROR -> MaterialTheme.colorScheme.errorContainer
        ToastMessage.Type.SUCCESS -> MaterialTheme.colorScheme.okContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (model.type) {
        ToastMessage.Type.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        ToastMessage.Type.SUCCESS -> MaterialTheme.colorScheme.onOkContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val contentIconColor = when (model.type) {
        ToastMessage.Type.ERROR -> MaterialTheme.colorScheme.error
        ToastMessage.Type.SUCCESS -> MaterialTheme.colorScheme.ok
        else -> contentColor
    }
    val contentIcon = when (model.type) {
        ToastMessage.Type.ERROR -> Icons.Outlined.ErrorOutline
        ToastMessage.Type.SUCCESS -> Icons.Outlined.CheckCircle
        ToastMessage.Type.INFO -> Icons.Outlined.Info
        else -> Icons.Outlined.Info
    }

    CompositionLocalProvider(
        LocalContentColor provides contentColor,
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(containerColor)
                .padding(contentPadding)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
            Icon(
                modifier = Modifier
                    .size(32.dp)
                    .padding(4.dp),
                imageVector = contentIcon,
                contentDescription = null,
                tint = contentIconColor,
            )
            Spacer(
                modifier = Modifier
                    .width(12.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    style = MaterialTheme.typography.titleSmall,
                    text = model.title,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
                if (model.text != null) {
                    Text(
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current
                            .combineAlpha(alpha = MediumEmphasisAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        text = model.text,
                    )
                }
            }
            if (model.action != null && !CurrentPlatform.hasWatch()) {
                Spacer(
                    modifier = Modifier
                        .width(8.dp),
                )
                TextButton(
                    onClick = model.action.onClick,
                ) {
                    Text(
                        text = model.action.title,
                        color = contentIconColor,
                    )
                }
            }
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
            // Error messages are often wanted to be shared. To save
            // people from the misery of retyping or screenshotting the
            // error message we add a button to copy the content.
            if (model.type == ToastMessage.Type.ERROR && !CurrentPlatform.hasWatch()) {
                val clipboardService by rememberInstance<ClipboardService>()
                IconButton(
                    onClick = {
                        val value = listOfNotNull(
                            model.title,
                            model.text,
                        ).joinToString(separator = "\n")
                        clipboardService.setPrimaryClip(
                            value = value,
                            concealed = false,
                        )
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(Res.string.copy),
                    )
                }
            } else {
                Spacer(
                    modifier = Modifier
                        .width(8.dp),
                )
            }
        }
    }
}


data class ToastMessageRenderDrawable(
    val key: String,
    val enterTransition: EnterTransition,
    val exitTransition: ExitTransition,
    val content: @Composable () -> Unit,
    /**
     * A callback that gets fired when a drawable gets
     * disposed.
     */
    val onDisposed: () -> Unit,
) {
    private val visibleState = MutableTransitionState(false)

    // By default, automatically expand the content
    // to visible state.
    init {
        visibleState.targetState = true
    }

    var isDisposed = false
        set(value) {
            field = value
            // notify the observer
            if (!value) onDisposed
        }

    fun dispose() {
        visibleState.targetState = false
    }

    @Composable
    fun Content() {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = enterTransition,
            exit = exitTransition,
        ) {
            content()
        }

        val shouldBeDisposed = !isDisposed &&
                // both current and target state are equal to 'false'
                !(visibleState.targetState || visibleState.currentState) &&
                // the transition is complete
                !visibleState.isIdle
        LaunchedEffect(shouldBeDisposed) {
            if (shouldBeDisposed) isDisposed = true
        }
    }
}

data class ToastMessageRenderItem(
    val drawable: ToastMessageRenderDrawable,
    /**
     * A job that removes the message from a
     * state after a delay.
     */
    val cancellationJob: Job,
)

private const val MSG_ERROR_DURATION = 4500L
private const val MSG_NORMAL_DURATION = 2500L
private const val MSG_ACTION_DURATION = 4500L

private val ToastMessage.duration
    get() = run {
        val typeDurationMs = when (type) {
            ToastMessage.Type.ERROR -> MSG_ERROR_DURATION
            else -> MSG_NORMAL_DURATION
        }
        val actionDurationMs = if (action != null) MSG_ACTION_DURATION else 0L
        maxOf(
            typeDurationMs,
            actionDurationMs,
        )
    }
