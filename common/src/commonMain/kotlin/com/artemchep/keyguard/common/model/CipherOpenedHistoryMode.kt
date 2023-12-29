package com.artemchep.keyguard.common.model

sealed interface CipherOpenedHistoryMode {
    data object Recent : CipherOpenedHistoryMode
    data object Popular : CipherOpenedHistoryMode
}
