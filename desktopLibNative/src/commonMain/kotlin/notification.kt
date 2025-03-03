import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@CName("postNotification")
public expect fun postNotification(
    id: Int,
    title: String,
    text: String,
): Int
