@file:JvmName("DSendFun")

package com.artemchep.keyguard.common.model

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import arrow.optics.optics
import com.artemchep.keyguard.common.util.flowOfTime
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.KeyguardAttachment
import com.artemchep.keyguard.ui.icons.KeyguardNote
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock
import kotlin.time.Instant

@optics
data class DSend(
    val id: String,
    val accountId: String,
    val accessId: String,
    val keyBase64: String?,
    val revisionDate: Instant,
    val createdDate: Instant?,
    val deletedDate: Instant?,
    val expirationDate: Instant?,
    val service: BitwardenService,
    // common
    val name: String,
    val notes: String,
    val accessCount: Int,
    val maxAccessCount: Int? = null,
    val hasPassword: Boolean,
    val synced: Boolean,
    val disabled: Boolean,
    val hideEmail: Boolean,
    val type: Type,
    val text: Text?,
    val file: File?,
) : HasAccountId {
    companion object {
    }

    val accentLight: Color
    val accentDark: Color

    init {
        val colors = generateAccentColors(service.remote?.id ?: id)
        accentLight = colors.light
        accentDark = colors.dark
    }

    enum class Type {
        None,
        File,
        Text,
    }

    data class File(
        val id: String,
        val fileName: String,
        val keyBase64: String?,
        val size: Long? = null,
    ) {
        companion object;
    }

    data class Text(
        val text: String? = null,
        val hidden: Boolean? = null,
    ) {
        companion object;
    }

    override fun accountId(): String = accountId
}

val DSend.expiredFlow: StateFlow<Boolean>
    get() = flowOfTime()
        .map { expired }
        .stateIn(
            scope = GlobalScope,
            started = SharingStarted.WhileSubscribed(1000L),
            initialValue = expired,
        )

val DSend.expired: Boolean
    get() = expirationDate != null &&
            expirationDate <= Clock.System.now()

fun DSend.Type.iconImageVector() = when (this) {
    DSend.Type.Text -> Icons.Outlined.KeyguardNote
    DSend.Type.File -> Icons.Outlined.KeyguardAttachment
    DSend.Type.None -> Icons.Stub
}

fun DSend.Type.titleH() = when (this) {
    DSend.Type.Text -> Res.string.send_type_text
    DSend.Type.File -> Res.string.send_type_file
    DSend.Type.None -> Res.string.send_type_unknown
}

fun DSend.Companion.requiresPremium(type: DSend.Type) = when (type) {
    DSend.Type.Text -> false
    DSend.Type.File -> true
    DSend.Type.None -> false
}
