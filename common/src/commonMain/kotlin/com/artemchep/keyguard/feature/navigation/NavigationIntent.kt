package com.artemchep.keyguard.feature.navigation

import kotlinx.collections.immutable.PersistentList

sealed interface NavigationIntent {
    data class Composite(
        val list: List<NavigationIntent>,
    ) : NavigationIntent

    // navigate

    data class NavigateToPreview(
        val uri: String,
        val fileName: String? = null,
    ) : NavigationIntent

    data class NavigateToPreviewInFileManager(
        val uri: String,
        val fileName: String? = null,
    ) : NavigationIntent

    data class NavigateToSend(
        val uri: String,
        val fileName: String? = null,
    ) : NavigationIntent

    data class NavigateToShare(
        val text: String,
    ) : NavigationIntent

    data class NavigateToLargeType(
        val phrases: List<String>,
        val colorize: Boolean,
    ) : NavigationIntent

    data class NavigateToApp(
        val packageName: String,
    ) : NavigationIntent

    data class NavigateToBrowser(
        val url: String,
    ) : NavigationIntent

    data class NavigateToPhone(
        val phoneNumber: String,
    ) : NavigationIntent

    data class NavigateToSms(
        val phoneNumber: String,
    ) : NavigationIntent

    data class NavigateToEmail(
        val email: String,
        val subject: String? = null,
        val body: String? = null,
    ) : NavigationIntent

    data class NavigateToMaps(
        val address1: String? = null,
        val address2: String? = null,
        val address3: String? = null,
        val city: String? = null,
        val state: String? = null,
        val postalCode: String? = null,
        val country: String? = null,
    ) : NavigationIntent

    data class NavigateToRoute(
        val route: Route,
        val launchMode: LaunchMode = LaunchMode.DEFAULT,
    ) : NavigationIntent {
        enum class LaunchMode {
            DEFAULT,
            SINGLE,
        }
    }

    data class NavigateToStack(
        val stack: List<NavigationEntry>,
    ) : NavigationIntent

    data class SetRoute(
        val route: Route,
    ) : NavigationIntent

    // pop

    data object Exit : NavigationIntent

    data object Pop : NavigationIntent

    data class PopById(
        val id: String,
        val exclusive: Boolean = true,
    ) : NavigationIntent

    // manual

    data class Manual(
        val handle: NavigationIntentScope.((Route) -> NavigationEntry) -> PersistentList<NavigationEntry>,
    ) : NavigationIntent
}
