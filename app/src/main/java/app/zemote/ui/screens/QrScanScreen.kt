package app.zemote.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.zemote.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/** 扫码配对页：相机取景 + ZXing 本地解码（不依赖 Google 服务） */
@Composable
fun QrScanScreen(
    onBack: () -> Unit,
    onResult: (String) -> Unit,
) {
    var hasPermission by remember { mutableStateOf(false) }
    var permissionAsked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionAsked = true
    }
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.CAMERA) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 页头
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.scan_add), style = MaterialTheme.typography.titleLarge)
        }

        if (hasPermission) {
            QrCameraView(onResult = onResult)
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.qr_permission_denied),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.qr_retry))
                    }
                }
            }
        }
    }
}

/** 相机取景 + 扫码框遮罩 + 解码回调 */
@Composable
private fun QrCameraView(onResult: (String) -> Unit) {
    val ctx = LocalContext.current
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val handled = remember { mutableStateOf(false) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    // 扫码结束后销毁线程池，防止 composable 重建时泄漏
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            },
            update = { previewView ->
                val future = ProcessCameraProvider.getInstance(previewView.context)
                future.addListener({
                    try {
                        val provider = future.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { proxy ->
                            if (handled.value) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            val text = decodeQr(proxy)
                            proxy.close()
                            if (text != null) {
                                handled.value = true
                                mainHandler.post { onResult(text) }
                            }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(previewView.context))
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 扫码框遮罩 + 四角
        val bracketColor = MaterialTheme.colorScheme.primary
        Canvas(modifier = Modifier.fillMaxSize()) {
            val boxSize = size.width * 0.72f
            val left = (size.width - boxSize) / 2
            val top = (size.height - boxSize) / 2 - size.height * 0.06f
            // 半透明遮罩（四块）
            val mask = Color.Black.copy(alpha = 0.55f)
            drawRect(mask, topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(size.width, top))
            drawRect(mask, topLeft = Offset(0f, top + boxSize), size = androidx.compose.ui.geometry.Size(size.width, size.height - top - boxSize))
            drawRect(mask, topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(left, boxSize))
            drawRect(mask, topLeft = Offset(left + boxSize, top), size = androidx.compose.ui.geometry.Size(size.width - left - boxSize, boxSize))
            // 四角括号
            val len = boxSize * 0.09f
            val stroke = 10f
            val c = bracketColor
            // 左上
            drawLine(c, Offset(left, top), Offset(left + len, top), stroke, StrokeCap.Round)
            drawLine(c, Offset(left, top), Offset(left, top + len), stroke, StrokeCap.Round)
            // 右上
            drawLine(c, Offset(left + boxSize, top), Offset(left + boxSize - len, top), stroke, StrokeCap.Round)
            drawLine(c, Offset(left + boxSize, top), Offset(left + boxSize, top + len), stroke, StrokeCap.Round)
            // 左下
            drawLine(c, Offset(left, top + boxSize), Offset(left + len, top + boxSize), stroke, StrokeCap.Round)
            drawLine(c, Offset(left, top + boxSize), Offset(left, top + boxSize - len), stroke, StrokeCap.Round)
            // 右下
            drawLine(c, Offset(left + boxSize, top + boxSize), Offset(left + boxSize - len, top + boxSize), stroke, StrokeCap.Round)
            drawLine(c, Offset(left + boxSize, top + boxSize), Offset(left + boxSize, top + boxSize - len), stroke, StrokeCap.Round)
        }

        Text(
            stringResource(R.string.qr_align),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 140.dp),
        )
    }
}

/** YUV → 亮度源 → ZXing 解码（处理相机旋转） */
private fun decodeQr(proxy: ImageProxy): String? {
    val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        ))
    }
    val buffer = proxy.planes[0].buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    var width = proxy.width
    var height = proxy.height
    val rotation = proxy.imageInfo.rotationDegrees

    val (payload, pw, ph) = when (rotation) {
        90 -> Triple(rotateYuv(data, width, height, 90), height, width)
        270 -> Triple(rotateYuv(data, width, height, 270), height, width)
        180 -> Triple(rotateYuv(data, width, height, 180), width, height)
        else -> Triple(data, width, height)
    }
    val source = PlanarYUVLuminanceSource(payload, pw, ph, 0, 0, pw, ph, false)
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}

/** 亮度平面旋转（仅旋转 Y 矩阵，扫码只用亮度） */
private fun rotateYuv(data: ByteArray, w: Int, h: Int, degrees: Int): ByteArray {
    val out = ByteArray(data.size)
    when (degrees) {
        90 -> {
            var i = 0
            for (x in 0 until w) for (y in h - 1 downTo 0) out[i++] = data[y * w + x]
        }
        180 -> {
            var i = 0
            for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) out[i++] = data[y * w + x]
        }
        270 -> {
            var i = 0
            for (x in w - 1 downTo 0) for (y in 0 until h) out[i++] = data[y * w + x]
        }
        else -> return data
    }
    return out
}
