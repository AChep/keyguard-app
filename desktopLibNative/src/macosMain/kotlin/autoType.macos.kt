import autotype.AutoTypeCommand
import kotlinx.coroutines.runBlocking
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@CName("autoType")
public actual fun autoType(
    payload: String,
): Unit = runBlocking {
    AutoTypeCommand.executeOrThrow(payload)
}
