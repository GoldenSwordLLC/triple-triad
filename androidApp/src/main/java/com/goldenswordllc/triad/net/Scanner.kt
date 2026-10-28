package com.goldenswordllc.triad.net

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.PlanarYUVLuminanceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private val Gold = Color(0xFFD4AF37)
private val GoldText = Color(255, 240, 200)
private val Cream = Color(245, 238, 220)

private fun packedY(proxy: ImageProxy): ByteArray {
    val w = proxy.width
    val h = proxy.height
    val plane = proxy.planes[0]
    val stride = plane.rowStride
    val buf = plane.buffer.duplicate()
    val out = ByteArray(w * h)
    if (stride == w) {
        buf.position(0)
        buf.get(out, 0, w * h)
        return out
    }
    val row = ByteArray(stride)
    for (r in 0 until h) {
        buf.position(r * stride)
        val n = minOf(stride, buf.remaining())
        buf.get(row, 0, n)
        System.arraycopy(row, 0, out, r * w, w)
    }
    return out
}

private fun rotateY(data: ByteArray, w: Int, h: Int): Triple<ByteArray, Int, Int> {
    val out = ByteArray(data.size)
    for (y in 0 until h) {
        val srcRow = y * w
        for (x in 0 until w) {
            out[x * h + (h - 1 - y)] = data[srcRow + x]
        }
    }
    return Triple(out, h, w)
}

private fun decodeQrYuv(proxy: ImageProxy): String? {
    val w = proxy.width
    val h = proxy.height
    val data = packedY(proxy)
    val rotated = rotateY(data, w, h)
    val candidates = if (proxy.imageInfo.rotationDegrees % 180 == 90) {
        listOf(rotated, Triple(data, w, h))
    } else {
        listOf(Triple(data, w, h), rotated)
    }
    val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        ))
    }
    for ((bytes, cw, ch) in candidates) {
        val source = PlanarYUVLuminanceSource(bytes, cw, ch, 0, 0, cw, ch, false)
        try {
            return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
            reader.reset()
        }
    }
    return null
}

@Composable
fun QrScannerScreen(onResult: (String?) -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val found = remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    LaunchedEffect(found.value) {
        if (found.value != null) {
            kotlinx.coroutines.delay(300)
            onResult(found.value)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(18, 12, 4))) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(30, 22, 8))
                    .border(2.dp, Gold, RoundedCornerShape(12.dp))
                    .clickable { onExit() }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("✕ Cancel", color = GoldText, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text("JOIN A MATCH", color = GoldText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }
        Text("Point your camera at the host's QR code", color = Cream, fontSize = 12.sp,
             textAlign = androidx.compose.ui.text.style.TextAlign.Center,
             modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
        if (granted) {
            val executor = remember { Executors.newSingleThreadExecutor() }
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { executor.shutdown() } }
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                factory = { ctx ->
                    PreviewView(ctx).also { previewView ->
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        var busy = false
                        analysis.setAnalyzer(executor) { proxy ->
                            try {
                                if (!busy && found.value == null) {
                                    busy = true
                                    val text = decodeQrYuv(proxy)
                                    busy = false
                                    if (text != null) found.value = text
                                }
                            } finally {
                                proxy.close()
                            }
                        }
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                })
        } else {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("📷", fontSize = 40.sp)
                Spacer(Modifier.height(10.dp))
                Text("Camera access is needed", color = GoldText, fontSize = 18.sp,
                     fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Point your camera at the host's QR code to join their match.",
                     color = Cream, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(120, 85, 20))
                        .border(2.dp, Gold, RoundedCornerShape(12.dp))
                        .clickable { permissionLauncher.launch(Manifest.permission.CAMERA) }
                        .padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text("GRANT CAMERA ACCESS", color = GoldText, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text("If it stays denied, enable Camera for Triad in your phone's Settings.",
                     color = Color(170, 150, 110), fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

suspend fun parseScannedInvite(raw: String?): QrInvite? =
    raw?.let { withContext(Dispatchers.Default) { parseInvite(it) } }
