import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@CName("autoType")
public actual fun autoType(payload: String) {
    println("Auto-type is not supported on this platform!")
}
