import kotlinx.cinterop.ByteVarOf
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@CName("biometricsIsSupported")
public expect fun biometricsIsSupported(): Boolean

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("biometricsVerify")
public expect fun biometricsVerify(
    title: String,
    callback: CPointer<CFunction<(Boolean, CPointer<ByteVarOf<Byte>>?) -> Unit>>,
)
