package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.constants.PredefinedIcon
import com.artemchep.keyguard.core.store.bitwarden.KeePassIcon

internal fun PredefinedIcon.toKeePassIcon(): KeePassIcon = KeePassIcon.valueOf(name)

internal fun KeePassIcon.toPredefinedIcon(): PredefinedIcon = PredefinedIcon.valueOf(name)
