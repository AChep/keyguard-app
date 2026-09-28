package com.artemchep.keyguard.feature.yubikey

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.artemchep.keyguard.util.yubikey.YubiKeyActivityProtocol
import com.artemchep.keyguard.util.yubikey.YubiKeyOperation
import com.artemchep.keyguard.util.yubikey.YubiKeyResult

internal class YubiKeyActivityContract : ActivityResultContract<YubiKeyOperation, Result<YubiKeyResult>>() {
    override fun createIntent(context: Context, input: YubiKeyOperation): Intent =
        YubiKeyActivityProtocol.createIntent(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Result<YubiKeyResult> =
        YubiKeyActivityProtocol.parseResult(resultCode, intent)
}
