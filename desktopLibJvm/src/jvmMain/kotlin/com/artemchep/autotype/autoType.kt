package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import com.artemchep.jna.util.DisposableScope
import com.artemchep.jna.util.asMemory

public suspend fun autoType(payload: String) {
    val lib = DesktopLibJna.get()
    val scope = DisposableScope()
    try {
        lib.autoType(
            payload = payload
                .asMemory()
                .let(scope::register),
        )
    } finally {
        scope.dispose()
    }
}
