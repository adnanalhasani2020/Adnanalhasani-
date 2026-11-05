package com.adnanalhasani.commercial

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal fun isValidGtinCheckDigit(value: String): Boolean {
    if (value.length !in setOf(8, 12, 13, 14) || !value.all(Char::isDigit)) return false
    val digits = value.map { it.digitToInt() }
    val check = digits.last()
    val sum = digits.dropLast(1).reversed().mapIndexed { index, digit ->
        digit * if (index % 2 == 0) 3 else 1
    }.sum()
    return (10 - (sum % 10)) % 10 == check
}

private fun barcodeFormatName(format: Int): String = when (format) {
    Barcode.FORMAT_EAN_13 -> "EAN-13"
    Barcode.FORMAT_EAN_8 -> "EAN-8"
    Barcode.FORMAT_UPC_A -> "UPC-A"
    Barcode.FORMAT_UPC_E -> "UPC-E"
    Barcode.FORMAT_CODE_128 -> "Code 128"
    Barcode.FORMAT_CODE_39 -> "Code 39"
    Barcode.FORMAT_QR_CODE -> "QR"
    Barcode.FORMAT_ITF -> "ITF"
    Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
    Barcode.FORMAT_PDF417 -> "PDF417"
    Barcode.FORMAT_AZTEC -> "Aztec"
    else -> "رمز شريطي"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BarcodeScannerScreen(onBack: () -> Unit, onBarcodeFound: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var detectedCode by remember { mutableStateOf("") }
    var detectedFormat by remember { mutableStateOf(0) }
    var cameraError by remember { mutableStateOf("") }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (!granted) cameraError = "لم تسمح باستخدام الكاميرا. يمكنك استخدام ماسح USB أو البحث اليدوي."
    }
    val scanner: BarcodeScanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(
            Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93,
            Barcode.FORMAT_CODABAR, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_ITF,
            Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_PDF417,
            Barcode.FORMAT_AZTEC
        ).build())
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val detectionClaimed = remember { AtomicBoolean(false) }

    LaunchedEffect(Unit) {
        if (!hasPermission) requestPermission.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(previewView, hasPermission, lifecycleOwner) {
        val view = previewView
        if (view != null && hasPermission) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(cameraExecutor) { proxy ->
                        val mediaImage = proxy.image
                        if (mediaImage == null || detectionClaimed.get()) {
                            proxy.close()
                        } else {
                            val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                            scanner.process(input)
                                .addOnSuccessListener { barcodes ->
                                    val barcode = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                                    val raw = barcode?.rawValue?.trim().orEmpty()
                                    if (raw.isNotBlank() && detectionClaimed.compareAndSet(false, true)) {
                                        mainHandler.post {
                                            detectedCode = raw
                                            detectedFormat = barcode?.format ?: 0
                                        }
                                    }
                                }
                                .addOnFailureListener { failure ->
                                    mainHandler.post { cameraError = failure.message ?: "تعذرت قراءة الرمز." }
                                }
                                .addOnCompleteListener { proxy.close() }
                        }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    view.tag = analysis
                }.onFailure {
                    mainHandler.post { cameraError = it.message ?: "تعذر تشغيل الكاميرا." }
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            (view?.tag as? ImageAnalysis)?.clearAnalyzer()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            scanner.close()
        }
    }

    val verifiedGtin = detectedFormat in setOf(Barcode.FORMAT_EAN_8, Barcode.FORMAT_EAN_13, Barcode.FORMAT_UPC_A) ||
        (detectedFormat == Barcode.FORMAT_ITF && detectedCode.length == 14)
    val checksumOk = when (detectedFormat) {
        Barcode.FORMAT_EAN_8 -> detectedCode.length == 8 && isValidGtinCheckDigit(detectedCode)
        Barcode.FORMAT_EAN_13 -> detectedCode.length == 13 && isValidGtinCheckDigit(detectedCode)
        Barcode.FORMAT_UPC_A -> detectedCode.length == 12 && isValidGtinCheckDigit(detectedCode)
        Barcode.FORMAT_ITF -> detectedCode.length != 14 || isValidGtinCheckDigit(detectedCode)
        else -> true
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("البحث بالباركود") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") } },
            actions = {
                IconButton(onClick = {
                    detectedCode = ""; detectedFormat = 0; cameraError = ""; detectionClaimed.set(false)
                }) { Icon(Icons.Default.Refresh, contentDescription = "إعادة المسح") }
            }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("وجّه الكاميرا نحو الرمز واتركه داخل الإطار. لن يُستخدم الرمز قبل مراجعته وتأكيده.",
                style = MaterialTheme.typography.bodyMedium)
            if (hasPermission) {
                AndroidView(
                    factory = { ctx -> PreviewView(ctx).also { it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE; previewView = it } },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            } else {
                Card(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(42.dp))
                        Text("إذن الكاميرا غير متاح")
                        Button(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }) { Text("السماح بالكاميرا") }
                    }
                }
            }
            if (cameraError.isNotBlank()) Text(cameraError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (detectedCode.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("الرمز المقروء", style = MaterialTheme.typography.labelMedium)
                        Text(detectedCode, style = MaterialTheme.typography.headlineSmall)
                        Text(barcodeFormatName(detectedFormat), style = MaterialTheme.typography.bodySmall)
                        if (verifiedGtin && !checksumOk) {
                            Text("فشل التحقق من طول الرمز أو رقم التدقيق؛ أعد المسح ولا تعتمد هذا الرمز.", color = MaterialTheme.colorScheme.error)
                        } else if (verifiedGtin) {
                            Text("اجتاز رقم التدقيق القياسي.", color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("راجع الرمز بصريًا قبل استخدامه.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(enabled = checksumOk, onClick = { onBarcodeFound(detectedCode) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text("استخدام الرمز في البحث")
                        }
                    }
                }
            }
        }
    }
}
