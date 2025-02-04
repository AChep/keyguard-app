import kotlinx.cinterop.ByteVarOf
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.invoke
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@CName("biometricsIsSupported")
public actual fun biometricsIsSupported(): Boolean = false

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("biometricsVerify")
public actual fun biometricsVerify(
    title: String,
    callback: CPointer<CFunction<(Boolean, CPointer<ByteVarOf<Byte>>?) -> Unit>>,
) {
    callback.invoke(false, null)
}
