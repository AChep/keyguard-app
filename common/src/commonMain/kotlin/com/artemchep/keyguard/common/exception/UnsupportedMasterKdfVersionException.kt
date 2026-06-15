package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.common.model.MasterKdfVersion

class UnsupportedMasterKdfVersionException(
    version: MasterKdfVersion,
    type: String,
) : RuntimeException("Unsupported $type KDF version: ${version.raw}")
