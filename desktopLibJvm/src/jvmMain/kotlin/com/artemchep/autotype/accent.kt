package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna

public fun getSystemAccentColor(): Int = runCatching {
    getSystemAccentColorOrDefault(
        lib = DesktopLibJna.get(),
    )
}.getOrDefault(0)
