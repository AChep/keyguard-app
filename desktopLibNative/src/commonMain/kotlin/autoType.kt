import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@CName("autoType")
public expect fun autoType(payload: String)
