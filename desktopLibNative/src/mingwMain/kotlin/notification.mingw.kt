import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@CName("postNotification")
public actual fun postNotification(
    id: Int,
    title: String,
    text: String
): Boolean = false
