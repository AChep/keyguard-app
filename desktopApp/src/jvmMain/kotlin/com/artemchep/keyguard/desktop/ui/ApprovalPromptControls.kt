package com.artemchep.keyguard.desktop.ui

/** What the content of an approval prompt shows and does. */
internal class ApprovalPromptControls(
    /** `true` while the approval is being verified. */
    val busy: Boolean,
    val onDeny: () -> Unit,
    /** Approves the request, or first asks for the user's identity if approving requires it. */
    val onAllow: () -> Unit,
)
