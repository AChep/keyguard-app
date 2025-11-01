package com.artemchep.keyguard.common.service.directorywatcher

import java.io.File

/**
 * @author Artem Chepurnyi
 */
data class FileWatchEvent(
    /**
     * Modified folder/file
     */
    val file: File,
    /**
     * Kind of file system event
     */
    val kind: Kind,
    /**
     * Extra data that should be associated with this event,
     * or `null` if none.
     */
    val tag: Any?,
) {
    /**
     * @author Artem Chepurnyi
     */
    enum class Kind(
        val kind: String,
    ) {
        /** Triggered upon initialization of the channel */
        INITIALIZED("initialized"),

        /** Triggered when file or directory is created */
        CREATED("created"),

        /** Triggered when file or directory is modified */
        MODIFIED("modified"),

        /** Triggered when file or directory is deleted */
        DELETED("deleted")
    }
}
