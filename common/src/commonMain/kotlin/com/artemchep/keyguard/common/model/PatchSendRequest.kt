package com.artemchep.keyguard.common.model

import arrow.core.None
import arrow.core.Option

data class PatchSendRequest(
    val patch: Map<String, Data>,
) {
    data class Data(
        val name: Option<String> = None,
        val hideEmail: Option<Boolean> = None,
        val disabled: Option<Boolean> = None,
        val password: Option<String?> = None,
        /**
         * Changes the file name of the send.
         * Note: only applied to sends with a file type.
         */
        val fileName: Option<String> = None,
    )
}
