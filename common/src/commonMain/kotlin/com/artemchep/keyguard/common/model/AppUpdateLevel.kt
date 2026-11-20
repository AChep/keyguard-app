package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.StringResource

/**
 * Which new releases the app tells the user about.
 */
enum class AppUpdateLevel(
    val key: String,
    val title: StringResource,
) {
    OFF(
        key = "off",
        title = Res.string.app_update_level_off,
    ),

    /** Only a new major version, e.g. 3.x.x to 4.0.0. */
    MAJOR(
        key = "major",
        title = Res.string.app_update_level_major,
    ),

    /** A new major or minor version, e.g. 3.8.x to 3.9.0. */
    MINOR(
        key = "minor",
        title = Res.string.app_update_level_minor,
    ),

    /** Any new release, including a rebuild of the same version. */
    ALL(
        key = "all",
        title = Res.string.app_update_level_all,
    ),
    ;

    companion object {
        val DEFAULT = ALL
    }
}
