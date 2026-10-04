package com.artemchep.keyguard.feature.remotepicker

/** A child of a remote folder. */
internal data class RemotePickerEntry(
    /** The path relative to the picker root, without surrounding slashes. */
    val path: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long?,
    /** Whether the file can be opened; a storage may list names it cannot address. */
    val isAddressable: Boolean = true,
)
