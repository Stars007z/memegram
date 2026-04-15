package com.example.memegram

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun QrScannerView(modifier: Modifier, onScanned: (String) -> Unit) {
    // TODO: реализовать через AVCaptureSession на iOS
    Text("QR-сканер недоступен на iOS")
}