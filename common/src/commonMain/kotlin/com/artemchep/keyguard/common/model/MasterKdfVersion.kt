package com.artemchep.keyguard.common.model

@JvmInline
value class MasterKdfVersion(
    val raw: Int,
) {
    companion object {
        val V0 = MasterKdfVersion(raw = 0)
        val V1 = MasterKdfVersion(raw = 1)
        val LATEST = V1

        fun fromRaw(raw: Int) = MasterKdfVersion(raw = raw)
    }
}
