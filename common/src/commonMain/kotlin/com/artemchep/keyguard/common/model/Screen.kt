package com.artemchep.keyguard.common.model

/**
 * @author Artem Chepurnyi
 */
sealed class Screen {
    data object On : Screen()
    data object Off : Screen()
}
