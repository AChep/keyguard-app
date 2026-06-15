package com.artemchep.keyguard.feature.navigation

typealias NavigationEntryFactory = (String?, Route) -> NavigationEntry

/**
 * Commands passed through the controller chain.
 *
 * Routers handle stack-related intents and pass platform intents up to the
 * Android/Desktop/Wear shell.
 */
sealed interface NavigationIntent {
    /**
     * Runs a sequence of intents as one stack update.
     */
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

    /**
     * Pushes a route onto the active router stack.
     */
    data class NavigateToRoute(
        val route: Route,
        val launchMode: LaunchMode = LaunchMode.DEFAULT,
    ) : NavigationIntent {
        enum class LaunchMode {
            DEFAULT,
            /**
             * Keeps only one route of this class in the current stack.
             */
            SINGLE,
        }
    }

    /**
     * Replaces the active stack with already-created entries.
     */
    data class NavigateToStack(
        val stack: List<NavigationEntry>,
    ) : NavigationIntent

    /**
     * Replaces the active stack with a single route.
     */
    data class SetRoute(
        val route: Route,
    ) : NavigationIntent

    // pop

    data object Exit : NavigationIntent

    data object Pop : NavigationIntent

    /**
     * Pops up to a specific entry id.
     */
    data class PopById(
        val id: String,
        val exclusive: Boolean = true,
    ) : NavigationIntent

    // manual

    /**
     * Gives callers direct access to transform the active stack.
     */
    data class Manual(
        val handle: NavigationIntentScope.(NavigationEntryFactory) -> NavigationBackStack,
    ) : NavigationIntent
}
