package com.artemchep.keyguard.common.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

enum class AppColors(
    val title: String,
    val key: String,
    val color: Int,
) {
    RED("Red", "red", -0xbbcca),
    PINK("Pink", "pink", -0x16e19d),
    PURPLE("Purple", "purple", -0x63d850),
    DEEP_PURPLE("Deep purple", "deep_purple", -0x98c549),
    INDIGO("Indigo", "indigo", -0xc0ae4b),
    BLUE("Blue", "blue", -0xde690d),
    CYAN("Cyan", "cyan", -0xff6859),
    TEAL("Teal", "teal", -0xff6978),
    GREEN("Green", "green", -0xbc5fb9),
    LIGHT_GREEN("Light green", "light_green", -0x743cb6),
    LIME("Lime", "lime", -0x3223c7),
    YELLOW("Yellow", "yellow", -0x14c5),
    AMBER("Amber", "amber", -0x3ef9),
    ORANGE("Orange", "orange", -0x6800),
    DEEP_ORANGE("Deep orange", "deep_orange", -0xa8de),
    BROWN("Brown", "brown", -0x86aab8),
    GRAY("Gray", "gray", Color.Gray.toArgb()),
}
