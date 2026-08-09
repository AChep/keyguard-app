package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.FolderHierarchyMode

/**
 * Resolves the folder representation supported by the account's provider.
 *
 * The returned mode is suitable for newly created root folders. The operation
 * fails if the account does not exist rather than guessing a representation.
 */
interface ResolveFolderHierarchyMode : (
    AccountId,
) -> IO<FolderHierarchyMode>
