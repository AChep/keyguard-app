package com.artemchep.keyguard.common.service.download

data class DownloadQueueRequest(
    val tag: DownloadInfoEntity.AttachmentDownloadTag,
    val source: Source,
    val name: String,
    val key: ByteArray? = null,
    val attempt: Int = 0,
    val scheduleBackground: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadQueueRequest) return false

        if (tag != other.tag) return false
        if (source != other.source) return false
        if (name != other.name) return false
        if (!key.contentEquals(other.key)) return false
        if (attempt != other.attempt) return false
        if (scheduleBackground != other.scheduleBackground) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tag.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + attempt
        result = 31 * result + scheduleBackground.hashCode()
        return result
    }

    sealed interface Source {
        val url: String
        val urlIsOneTime: Boolean

        data class Url(
            override val url: String,
            override val urlIsOneTime: Boolean,
        ) : Source

        data class Direct(
            override val url: String,
            override val urlIsOneTime: Boolean,
            val data: ByteArray,
        ) : Source {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Direct) return false

                if (url != other.url) return false
                if (urlIsOneTime != other.urlIsOneTime) return false
                if (!data.contentEquals(other.data)) return false

                return true
            }

            override fun hashCode(): Int {
                var result = url.hashCode()
                result = 31 * result + urlIsOneTime.hashCode()
                result = 31 * result + data.contentHashCode()
                return result
            }
        }
    }
}
