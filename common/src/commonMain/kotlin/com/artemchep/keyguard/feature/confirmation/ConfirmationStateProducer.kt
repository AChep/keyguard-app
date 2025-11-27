package com.artemchep.keyguard.feature.confirmation

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.common.util.flow.combineToList
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.home.vault.add.AddState
import com.artemchep.keyguard.feature.home.vault.add.attachment.SkeletonAttachment
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import java.util.*

@Composable
fun confirmationState(
    args: ConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<ConfirmationResult>,
): ConfirmationState = with(localDI().direct) {
    confirmationState(
        args = args,
        transmitter = transmitter,
        windowCoroutineScope = instance(),
    )
}

@Composable
fun confirmationState(
    args: ConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<ConfirmationResult>,
    windowCoroutineScope: WindowCoroutineScope,
): ConfirmationState = produceScreenState(
    key = "confirmation",
    initial = ConfirmationState(
        sideEffects = ConfirmationState.SideEffects(),
        items = if (args.items.isEmpty()) {
            Loadable.Ok(emptyList())
        } else Loadable.Loading,
    ),
    args = arrayOf(
        windowCoroutineScope,
    ),
) {
    fun createItemKey(key: String) = "item.$key"

    val filePickerIntentSink = EventFlow<FilePickerIntent<*>>()

    val sideEffects = ConfirmationState.SideEffects(
        filePickerIntentFlow = filePickerIntentSink,
    )

    val itemsFlow = args.items
        .map { item ->
            val sink = mutablePersistedFlow(createItemKey(item.key)) {
                item.value
            }
            val state = when (item) {
                is ConfirmationRoute.Args.Item.BooleanItem -> null
                is ConfirmationRoute.Args.Item.StringItem ->
                    mutableComposeState<String>(sink as MutableStateFlow<String>)

                is ConfirmationRoute.Args.Item.EnumItem -> null
                is ConfirmationRoute.Args.Item.FileItem -> null
            }
            sink
                .map { value ->
                    when (item) {
                        is ConfirmationRoute.Args.Item.BooleanItem -> ConfirmationState.Item.BooleanItem(
                            key = item.key,
                            title = item.title,
                            text = item.text,
                            value = value as Boolean,
                            onChange = sink::value::set,
                        )

                        is ConfirmationRoute.Args.Item.StringItem -> {
                            val fixed = value as String
                            val error = if (item.canBeEmpty || fixed.isNotBlank()) {
                                null
                            } else {
                                translate(Res.string.error_must_not_be_blank)
                            }
                            val sensitive =
                                item.type == ConfirmationRoute.Args.Item.StringItem.Type.Password ||
                                        item.type == ConfirmationRoute.Args.Item.StringItem.Type.Token
                            val monospace =
                                item.type == ConfirmationRoute.Args.Item.StringItem.Type.Password ||
                                        item.type == ConfirmationRoute.Args.Item.StringItem.Type.Token ||
                                        item.type == ConfirmationRoute.Args.Item.StringItem.Type.Regex ||
                                        item.type == ConfirmationRoute.Args.Item.StringItem.Type.Command
                            val password =
                                item.type == ConfirmationRoute.Args.Item.StringItem.Type.Password
                            val generator = when (item.type) {
                                ConfirmationRoute.Args.Item.StringItem.Type.Username -> ConfirmationState.Item.StringItem.Generator.Username
                                ConfirmationRoute.Args.Item.StringItem.Type.Password -> ConfirmationState.Item.StringItem.Generator.Password
                                ConfirmationRoute.Args.Item.StringItem.Type.Token,
                                ConfirmationRoute.Args.Item.StringItem.Type.Text,
                                ConfirmationRoute.Args.Item.StringItem.Type.URI,
                                ConfirmationRoute.Args.Item.StringItem.Type.Regex,
                                ConfirmationRoute.Args.Item.StringItem.Type.Command,
                                -> null
                            }
                            requireNotNull(state)
                            val model = TextFieldModel2(
                                state = state,
                                text = fixed,
                                hint = item.hint,
                                error = error,
                                onChange = state::value::set,
                            )
                            ConfirmationState.Item.StringItem(
                                key = item.key,
                                title = item.title,
                                description = item.description,
                                sensitive = sensitive,
                                monospace = monospace,
                                password = password,
                                generator = generator,
                                value = fixed,
                                state = model,
                            )
                        }

                        is ConfirmationRoute.Args.Item.EnumItem -> {
                            val fixed = value as String
                            ConfirmationState.Item.EnumItem(
                                key = item.key,
                                value = fixed,
                                items = item.items
                                    .map { el ->
                                        ConfirmationState.Item.EnumItem.Item(
                                            key = el.key,
                                            title = el.title,
                                            text = el.text,
                                            selected = el.key == fixed,
                                            onClick = {
                                                sink.value = el.key
                                            },
                                        )
                                    },
                                doc = item.docs[fixed]
                                    ?.let { doc ->
                                        ConfirmationState.Item.EnumItem.Doc(
                                            text = doc.text,
                                            onLearnMore = if (doc.url != null) {
                                                // lambda
                                                {
                                                    val intent =
                                                        NavigationIntent.NavigateToBrowser(doc.url)
                                                    navigate(intent)
                                                }
                                            } else {
                                                null
                                            },
                                        )
                                    },
                            )
                        }

                        is ConfirmationRoute.Args.Item.FileItem -> {
                            val fixed = value as ConfirmationRoute.Args.Item.FileItem.File?
                            val error = if (fixed != null) null else "Must pick a file"
                            ConfirmationState.Item.FileItem(
                                key = item.key,
                                title = item.title,
                                value = fixed,
                                error = error,
                                onSelect = {
                                    val intent = FilePickerIntent.OpenDocument(
                                        mimeTypes = arrayOf(
                                            "text/plain",
                                            "text/wordlist",
                                        )
                                    ) { info ->
                                        if (info != null) {
                                            val file = ConfirmationRoute.Args.Item.FileItem.File(
                                                uri = info.uri.toString(),
                                                name = info.name,
                                                size = info.size,
                                            )
                                            sink.value = file
                                        }
                                    }
                                    filePickerIntentSink.emit(intent)
                                },
                                onClear = if (fixed != null) {
                                    // lambda
                                    {
                                        sink.value = null
                                    }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
        }
        .combineToList()
    itemsFlow
        .map { items ->
            val valid = items.all { it.valid }
            ConfirmationState(
                sideEffects = sideEffects,
                items = Loadable.Ok(items),
                onDeny = {
                    transmitter(ConfirmationResult.Deny)
                    navigatePopSelf()
                },
                onConfirm = if (valid) {
                    // lambda
                    {
                        val data = items
                            .associate { it.key to it.value }
                        val result = ConfirmationResult.Confirm(data)
                        transmitter(result)
                        navigatePopSelf()
                    }
                } else {
                    null
                },
            )
        }
}
