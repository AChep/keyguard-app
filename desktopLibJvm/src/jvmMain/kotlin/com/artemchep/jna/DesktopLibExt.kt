package com.artemchep.jna

import com.artemchep.jna.util.DisposableScope

internal inline fun <T> withDesktopLib(
    crossinline block: DisposableScope.(DesktopLibJna) -> T,
): T {
    val lib = DesktopLibJna.get()
    val scope = DisposableScope()
    try {
        return block(scope, lib)
    } finally {
        scope.dispose()
    }
}
