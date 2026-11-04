package com.artemchep.keyguard.feature.agent.history

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.home.vault.model.VaultItemPresentation

/**
 * Everything an agent request log stores about one request, shared by
 * the SSH, GPG and CLI logs and by every platform's details dialog.
 */
@Immutable
data class AgentHistoryDetails(
    val id: String,
    val icon: AgentHistoryDetailsIcon,
    /** What the caller asked for, e.g. "Sign data". */
    val title: String,
    val outcome: AgentHistoryOutcome,
    val outcomeText: String,
    val formattedDate: String,
    val sections: List<Section>,
) {
    @Immutable
    data class Section(
        val id: String,
        /** `null` if the rows speak for themselves. */
        val title: String?,
        val rows: List<Row>,
        /** Show the rows as one table of labels and values. */
        val table: Boolean = false,
    )

    @Immutable
    sealed interface Row {
        val id: String

        @Immutable
        data class Text(
            override val id: String,
            val icon: AgentHistoryDetailsIcon?,
            val label: String,
            val value: String,
            val monospace: Boolean = false,
            val copyType: CopyText.Type = CopyText.Type.VALUE,
        ) : Row

        /** A long value, e.g. a command line, shown in full. */
        @Immutable
        data class Code(
            override val id: String,
            val icon: AgentHistoryDetailsIcon,
            val label: String,
            val value: String,
        ) : Row

        /**
         * The vault item that the request used; the [item] is
         * `null` if the item no longer exists.
         */
        @Immutable
        data class Reference(
            override val id: String,
            val label: String,
            val cipherId: String,
            val accountId: String?,
            val item: VaultItemPresentation?,
        ) : Row
    }
}

/** How a request ended, as one of the colors the UI shows. */
enum class AgentHistoryOutcome {
    SUCCESS,
    FAILURE,
    NEUTRAL,
}

/** A platform-neutral icon; each platform maps it to its own icon set. */
enum class AgentHistoryDetailsIcon {
    // Requests
    LIST_KEYS,
    SSH_SIGN,
    GPG_SIGN,
    GPG_DECRYPT,
    RUN,
    MCP,
    ENV_FILE,
    UNKNOWN,

    // Values
    APPLICATION,
    APPLICATION_PATH,
    PROCESS,
    EXECUTABLE,
    PROCESS_ID,
    FINGERPRINT,
    KEYGRIP,
    CLIENT,
    ERROR,
}
