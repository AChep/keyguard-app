package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AddCredentialCipherRequest

/**
 * Stores a credential in an editable login. An unavailable or unsuitable target fails the IO.
 * A new passkey always returns [Result.Stored]; an existing password may return [Result.Unchanged].
 */
interface AddCredentialCipher : (
    AddCredentialCipherRequest,
) -> IO<AddCredentialCipher.Result> {
    enum class Result {
        Stored,
        Unchanged,
    }
}
