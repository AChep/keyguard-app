package com.artemchep.keyguard.feature.passwordmemory

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.DialogRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteDescriptor
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.password_action_test_memory_title
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.icon

internal data class PasswordMemoryRoute(
    val args: Args,
) : DialogRoute {
    override val descriptor get() = RouteDescriptor.PasswordMemory

    internal data class Args(
        val password: String,
    )

    companion object {
        fun testPasswordMemoryActionOrNull(
            password: String,
            navigate: (NavigationIntent) -> Unit,
        ): FlatItemAction? = password
            .takeIf { it.isNotEmpty() }
            ?.let {
                FlatItemAction(
                    id = "cipher.password.testMemory",
                    leading = icon(Icons.Outlined.Psychology),
                    title = Res.string.password_action_test_memory_title.wrap(),
                    onClick = {
                        val route = PasswordMemoryRoute(
                            args = Args(
                                password = password,
                            ),
                        )
                        navigate(NavigationIntent.NavigateToRoute(route))
                    },
                )
            }
    }

    @Composable
    override fun Content() {
        PasswordMemoryScreen(
            args = args,
        )
    }
}
