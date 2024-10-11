package com.artemchep.keyguard.feature.qr

import android.Manifest
import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.SmallToolbar
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.jetbrains.compose.resources.stringResource
import java.util.concurrent.Executors

@Composable
fun ScanQrScreen(
    transmitter: RouteResultTransmitter<String>,
) {
    val state = scanQrScreenState()

    CollectedEffect(state.effects.onSuccessFlow) { rawValue ->
        // Notify that we have successfully logged in, and that
        // the caller can now decide what to do.
        transmitter.invoke(rawValue)
    }

    ScanQrScreen(
        state = state,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanQrScreen(
    state: ScanQrState,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            SmallToolbar(
                modifier = Modifier
                    .statusBarsPadding(),
                title = {
                    Text(stringResource(Res.string.scanqr_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        ScanQrCamera2(
            state = state,
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ScanQrCamera2(
    state: ScanQrState,
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    when (val status = cameraPermissionState.status) {
        // If the camera permission is granted, then show screen with the feature enabled
        PermissionStatus.Granted -> {
            ScanQrCamera(
                modifier = Modifier
                    .padding(16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .aspectRatio(1f),
                state = state,
            )
        }

        is PermissionStatus.Denied -> {
            Column(
                modifier = Modifier
                    .padding(horizontal = Dimens.horizontalPadding),
            ) {
                val textToShow = stringResource(Res.string.scanqr_camera_permission_required_text)
                Text(textToShow)
                Button(
                    modifier = Modifier
                        .padding(top = Dimens.verticalPadding),
                    onClick = {
                        cameraPermissionState.launchPermissionRequest()
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.grant_permission),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ScanQrCamera(
    modifier: Modifier = Modifier,
    state: ScanQrState,
) {
    val onScan by rememberUpdatedState(state.onScan)
    val analyzer = remember {
        QrImageAnalyzer { barcodes ->
            onScan?.invoke(barcodes)
        }
    }
    val analyserExecutor = remember {
        Executors.newSingleThreadExecutor()
    }
    DisposableEffect(analyserExecutor) {
        onDispose {
            analyserExecutor.shutdown()
        }
    }

    // TODO: Local lifecycle owner is a wrong guy!!!
    val lifecycleOwner by rememberUpdatedState(LocalLifecycleOwner.current)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val previewView = PreviewView(context)

            val cameraExecutor = ContextCompat.getMainExecutor(context)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    imageAnalysis.setAnalyzer(cameraExecutor, analyzer)
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            imageAnalysis,
                            preview,
                        )
                    } catch (_: Exception) {
                        // Do nothing.
                    }
                },
                cameraExecutor,
            )

            previewView
        },
        update = { previewView ->
        },
    )
}

private class QrImageAnalyzer(
    private val onReadBarcode: (barcodes: List<Barcode>) -> Unit,
) : ImageAnalysis.Analyzer {
    override fun analyze(imageProxy: ImageProxy) {
        scanBarcode(imageProxy)
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun scanBarcode(imageProxy: ImageProxy) {
        imageProxy.image?.let { image ->
            val inputImage = InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees)
            val scanner = BarcodeScanning.getClient()
            scanner
                .process(inputImage)
                .addOnCompleteListener {
                    imageProxy.close()

                    // Send the barcodes.
                    if (it.isSuccessful) onReadBarcode(it.result as List<Barcode>)
                }
        }
    }
}
