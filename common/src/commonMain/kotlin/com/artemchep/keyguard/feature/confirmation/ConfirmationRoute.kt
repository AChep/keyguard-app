package com.artemchep.keyguard.feature.confirmation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.platform.LeUri
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import kotlinx.serialization.Serializable

class ConfirmationRoute(
    val args: Args,
) : DialogRouteForResult<ConfirmationResult> {
    data class Args(
        val icon: (@Composable () -> Unit)? = null,
        val title: String? = null,
        val subtitle: String? = null,
        val message: String? = null,
        val items: List<Item<Any?>> = emptyList(),
        val docUrl: String? = null,
    ) {
        sealed interface Item<out T> {
            val key: String
            val value: T

            data class BooleanItem(
                override val key: String,
                override val value: Boolean = false,
                val title: String,
                val text: String? = null,
            ) : Item<Boolean>

            data class StringItem(
                override val key: String,
                override val value: String = "",
                val title: String,
                val hint: String? = null,
                val description: String? = null,
                val type: Type = Type.Text,
                /**
                 * `true` if the empty value is a valid
                 * value, `false` otherwise.
                 */
                val canBeEmpty: Boolean = true,
            ) : Item<String> {
                enum class Type {
                    Text,
                    URI,
                    Token,
                    Regex,
                    Command,
                    Password,
                    Username,
                }
            }

            data class EnumItem(
                override val key: String,
                override val value: String = "",
                val items: List<Item>,
                val docs: Map<String, Doc> = emptyMap(),
            ) : Item<String> {
                data class Item(
                    val key: String,
                    val icon: ImageVector? = null,
                    val title: String,
                    val text: String? = null,
                )

                data class Doc(
                    val text: String,
                    val url: String? = null,
                )
            }

            data class FileItem(
                override val key: String,
                override val value: File? = null,
                val title: String,
            ) : Item<FileItem.File?> {
                @LeParcelize
                @Serializable
                data class File(
                    val uri: String,
                    val name: String?,
                    val size: Long?,
                ) : LeParcelable
            }
        }
    }

    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<ConfirmationResult>,
    ) {
        ConfirmationScreen(
            args = args,
            transmitter = transmitter,
        )
    }
}

fun RememberStateFlowScope.createConfirmationDialogIntent(
    icon: (@Composable () -> Unit)? = null,
    title: String? = null,
    message: String? = null,
    onSuccess: () -> Unit,
): NavigationIntent {
    val route = registerRouteResultReceiver(
        route = ConfirmationRoute(
            args = ConfirmationRoute.Args(
                icon = icon,
                title = title,
                message = message,
            ),
        ),
    ) { result ->
        if (result is ConfirmationResult.Confirm) {
            onSuccess()
        }
    }
    return NavigationIntent.NavigateToRoute(route)
}

inline fun <reified T> RememberStateFlowScope.createConfirmationDialogIntent(
    item: ConfirmationRoute.Args.Item<T>,
    noinline icon: (@Composable () -> Unit)? = null,
    title: String? = null,
    message: String? = null,
    noinline onSuccess: (T) -> Unit,
): NavigationIntent {
    val route = registerRouteResultReceiver(
        route = ConfirmationRoute(
            args = ConfirmationRoute.Args(
                icon = icon,
                title = title,
                message = message,
                items = listOf(item),
            ),
        ),
    ) { result ->
        if (result is ConfirmationResult.Confirm) {
            val arg = result.data[item.key]
            if (arg !is T) {
                return@registerRouteResultReceiver
            }
            onSuccess(arg)
        }
    }
    return NavigationIntent.NavigateToRoute(route)
}
