package com.artemchep.keyguard.feature.barcodetype

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import arrow.core.Either
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.common.model.BarcodeImageRequest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.GetBarcodeImage
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.KeepScreenOnEffect
import com.artemchep.keyguard.ui.icons.DropdownIcon
import com.artemchep.keyguard.ui.icons.icon
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

@Composable
fun BarcodeTypeScreen(
    args: BarcodeTypeRoute.Args,
) {
    val loadableState = produceBarcodeTypeScreenState(args)
    BarcodeTypeContent(
        args = args,
        loadableState = loadableState,
    )
}

@Composable
private fun BarcodeTypeContent(
    args: BarcodeTypeRoute.Args,
    loadableState: Loadable<BarcodeTypeState>,
) {
    KeepScreenOnEffect()

    Dialog(
        icon = icon(Icons.Outlined.QrCode),
        title = {
            Text(stringResource(Res.string.barcodetype_title))
        },
        content = {
            Column {
                val dropdown = loadableState.getOrNull()?.format?.options.orEmpty()
                FlatDropdown(
                    content = {
                        FlatItemTextContent(
                            title = {
                                val selectedTitle =
                                    loadableState.getOrNull()?.format?.format.orEmpty()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = selectedTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Spacer(
                                        modifier = Modifier
                                            .width(8.dp),
                                    )
                                    DropdownIcon()
                                }
                            },
                        )
                    },
                    dropdown = dropdown,
                )
                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )

                val imageRequest = loadableState.getOrNull()?.request
                val imageAspectRatio = imageRequest?.format?.aspectRatio() ?: 1f
                BoxWithConstraints(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .fillMaxWidth(),
                ) {
                    val density = LocalDensity.current
                    val imageWidth = maxWidth.value.times(density.density).toInt()
                    val imageHeight = imageWidth
                        .div(imageAspectRatio)
                        .toInt()
                    BarcodeImage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        imageModel = {
                            imageRequest
                                ?.copy(
                                    size = BarcodeImageRequest.Size(
                                        width = imageWidth,
                                        height = imageHeight,
                                    ),
                                )
                        },
                    )
                }

                if (args.text != null) {
                    Spacer(
                        modifier = Modifier
                            .height(16.dp),
                    )
                    Text(
                        modifier = Modifier
                            .padding(horizontal = 16.dp),
                        text = args.text,
                    )
                }
            }
        },
        actions = {
            val updatedOnClose by rememberUpdatedState(loadableState.getOrNull()?.onClose)
            TextButton(
                enabled = updatedOnClose != null,
                onClick = {
                    updatedOnClose?.invoke()
                },
            ) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}

@Composable
private fun BarcodeImage(
    imageModel: () -> BarcodeImageRequest?,
    modifier: Modifier,
) {
    val imageState = remember {
        mutableStateOf<Either<Throwable, ImageBitmap>?>(null)
    }

    val getBarcodeImage: GetBarcodeImage by rememberInstance()
    LaunchedEffect(
        getBarcodeImage,
        imageModel,
    ) {
        val image = imageModel()
            ?.let(getBarcodeImage)
            ?.handleErrorTap {
                it.printStackTrace()
            }
            ?.attempt()
            ?.bind()
        imageState.value = image
    }

    BoxWithConstraints(
        modifier = modifier,
    ) {
        val image = imageState.value
            ?: return@BoxWithConstraints
        image.fold(
            ifLeft = { e ->
                Text(
                    modifier = Modifier
                        .padding(8.dp),
                    text = e.localizedMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            ifRight = { img ->
                Image(
                    bitmap = img,
                    contentDescription = null,
                )
            },
        )
    }
}

private fun BarcodeImageFormat.aspectRatio() = when (this) {
    BarcodeImageFormat.AZTEC -> 1f // square
    BarcodeImageFormat.CODABAR -> 2f // bar
    BarcodeImageFormat.CODE_39 -> 2f
    BarcodeImageFormat.CODE_93 -> 2f
    BarcodeImageFormat.CODE_128 -> 2f
    BarcodeImageFormat.DATA_MATRIX -> 1f
    BarcodeImageFormat.EAN_8 -> null
    BarcodeImageFormat.EAN_13 -> null
    BarcodeImageFormat.ITF -> null
    BarcodeImageFormat.MAXICODE -> null
    BarcodeImageFormat.PDF_417 -> 2f
    BarcodeImageFormat.QR_CODE -> 1f
    BarcodeImageFormat.RSS_14 -> null
    BarcodeImageFormat.RSS_EXPANDED -> null
    BarcodeImageFormat.UPC_A -> null
    BarcodeImageFormat.UPC_E -> null
    BarcodeImageFormat.UPC_EAN_EXTENSION -> null
}
