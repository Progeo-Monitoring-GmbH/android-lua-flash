package com.progeo.core.qr

import android.Manifest
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.progeo.core.permission.PermissionGate
import com.progeo.core.ui.theme.ProGeoColors
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Feeds camera frames to a [CodeDecoder]; reports the first code once. */
private class CodeAnalyzer(formats: Set<CodeFormat>, private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val decoder = CodeDecoder(formats)
    private val found = AtomicBoolean(false)
    private var bytes = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        image.use {
            if (found.get()) return
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val needed = rowStride * image.height
            if (bytes.size < needed) bytes = ByteArray(needed)
            val buffer = plane.buffer
            buffer.rewind()
            buffer.get(bytes, 0, minOf(buffer.remaining(), needed))
            val text = decoder.decode(bytes, image.width, image.height, rowStride) ?: return
            if (found.compareAndSet(false, true)) onCode(text)
        }
    }
}

/**
 * Camera preview that reads [formats] and calls [onCode] (on the main thread) with the first
 * code it finds. Needs the camera permission; [CodeScanDialog] asks for it.
 */
@Composable
fun CodeScanner(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    formats: Set<CodeFormat> = CodeFormat.QR_ONLY,
    torch: Boolean = false,
    onTorchAvailable: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner, formats) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val p = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .build()
            analysis.setAnalyzer(analysisExecutor, CodeAnalyzer(formats) { text -> mainExecutor.execute { currentOnCode(text) } })
            p.unbindAll()
            camera = runCatching { p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }.getOrNull()
            onTorchAvailable(camera?.cameraInfo?.hasFlashUnit() == true)
        }, mainExecutor)
        onDispose {
            provider?.unbindAll()
            camera = null
            analysisExecutor.shutdown()
        }
    }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Full-screen scanner: asks for the camera, shows a viewfinder and returns the first code
 * through [onCode] (the dialog does not close itself).
 */
@Composable
fun CodeScanDialog(
    onDismiss: () -> Unit,
    onCode: (String) -> Unit,
    title: String = "Scan code",
    hint: String = "Hold the code inside the frame.",
    formats: Set<CodeFormat> = CodeFormat.QR_ONLY,
) {
    val haptics = LocalHapticFeedback.current
    var torch by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            PermissionGate(
                permission = Manifest.permission.CAMERA,
                rationale = "The camera reads the code. Nothing is recorded or sent anywhere.",
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Box(Modifier.fillMaxSize()) {
                    CodeScanner(
                        onCode = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCode(it)
                        },
                        formats = formats,
                        torch = torch,
                        onTorchAvailable = { torchAvailable = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Viewfinder(Modifier.fillMaxSize())
                    Text(
                        hint,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 32.dp, vertical = 48.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (torchAvailable) {
                    IconButton(onClick = { torch = !torch }) {
                        Icon(
                            if (torch) Icons.Default.FlashlightOff else Icons.Default.FlashlightOn,
                            contentDescription = if (torch) "Light off" else "Light on",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/** Darkens everything but a centered square and marks its corners in ProGeo orange. */
@Composable
private fun Viewfinder(modifier: Modifier) {
    Canvas(modifier) {
        val side = minOf(size.width, size.height) * 0.68f
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        val radius = 24.dp.toPx()
        val cutout = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(left, top, left + side, top + side, CornerRadius(radius)))
        }
        drawPath(cutout, Color.Black.copy(alpha = 0.55f))
        val stroke = 5.dp.toPx()
        val arm = side * 0.14f
        val corners = listOf(
            Offset(left, top) to Offset(1f, 1f),
            Offset(left + side, top) to Offset(-1f, 1f),
            Offset(left, top + side) to Offset(1f, -1f),
            Offset(left + side, top + side) to Offset(-1f, -1f),
        )
        for ((corner, dir) in corners) {
            drawLine(ProGeoColors.Orange, corner, corner + Offset(arm * dir.x, 0f), stroke, StrokeCap.Round)
            drawLine(ProGeoColors.Orange, corner, corner + Offset(0f, arm * dir.y), stroke, StrokeCap.Round)
        }
    }
}
