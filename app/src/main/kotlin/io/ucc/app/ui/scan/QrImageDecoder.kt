package io.ucc.app.ui.scan

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Decodes QR codes from a gallery image with the same on-device ML Kit model the
 * camera path uses. Nothing about the image or payload is logged.
 */
object QrImageDecoder {
    suspend fun decode(context: Context, uri: Uri): QrImageResult = withContext(Dispatchers.IO) {
        val input = try {
            InputImage.fromFilePath(context, uri)
        } catch (_: Exception) {
            // IOException / IllegalArgumentException: not an image we can read.
            return@withContext QrImageResult.InvalidImage
        }
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        try {
            suspendCancellableCoroutine { cont ->
                scanner.process(input)
                    .addOnSuccessListener { codes -> cont.resume(QrImageResult.fromRawValues(codes.map { it.rawValue })) }
                    .addOnFailureListener { cont.resume(QrImageResult.InvalidImage) }
            }
        } finally {
            scanner.close()
        }
    }
}
