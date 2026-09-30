package com.artemchep.keyguard

import com.artemchep.keyguard.copy.AppleManagedImportFiles

/**
 * The directory the SwiftUI layer copies one picked or dropped file into, so
 * that the shared code can later delete the copy. The caller creates it.
 */
fun nextManagedImportDirectory(): String =
    AppleManagedImportFiles.newDirectory().toString()
