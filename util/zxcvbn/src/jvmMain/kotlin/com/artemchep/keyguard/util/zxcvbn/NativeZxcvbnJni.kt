package com.artemchep.keyguard.util.zxcvbn

// JNI export names in keyguard-zxcvbn-jni are pinned to this package. Moving
// this object compiles but fails at runtime with UnsatisfiedLinkError.
// Keeping the complete fixed ABI together makes JNI name parity auditable.
internal object NativeZxcvbnJni {
    external fun abiVersion(): Int

    external fun estimate(
        password: String,
        userInputs: Array<String>?,
        out: LongArray,
    ): Long
}
