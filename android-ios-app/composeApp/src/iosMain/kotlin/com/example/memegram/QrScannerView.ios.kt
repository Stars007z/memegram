package com.example.memegram

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.*
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.QuartzCore.CATransaction
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_async

@Composable
actual fun QrScannerView(modifier: Modifier, onScanned: (String) -> Unit) {
    var status by remember {
        mutableStateOf(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo))
    }

    LaunchedEffect(Unit) {
        if (status == AVAuthorizationStatusNotDetermined) {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                dispatch_async(dispatch_get_main_queue()) {
                    status = if (granted) AVAuthorizationStatusAuthorized else AVAuthorizationStatusDenied
                }
            }
        }
    }

    if (status != AVAuthorizationStatusAuthorized) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Нужен доступ к камере")
                Button(onClick = {
                    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                        dispatch_async(dispatch_get_main_queue()) {
                            status = if (granted) AVAuthorizationStatusAuthorized else AVAuthorizationStatusDenied
                        }
                    }
                }) { Text("Разрешить") }
            }
        }
        return
    }

    val scannedFlag = remember { Holder(false) }
    val sessionHolder = remember { SessionHolder() }

    DisposableEffect(Unit) {
        onDispose { sessionHolder.session?.stopRunning() }
    }

    UIKitView(
        factory = {
            val view = CameraPreviewView()
            val session = AVCaptureSession().apply {
                sessionPreset = AVCaptureSessionPresetHigh
            }
            sessionHolder.session = session

            val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            if (device != null) {
                val errorRef = nativeHeap_alloc_NSError()
                val input = AVCaptureDeviceInput.deviceInputWithDevice(device, errorRef)
                if (input != null && session.canAddInput(input)) {
                    session.addInput(input)
                }
                val output = AVCaptureMetadataOutput()
                if (session.canAddOutput(output)) {
                    session.addOutput(output)
                    val delegate = object : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
                        override fun captureOutput(
                            output: AVCaptureOutput,
                            didOutputMetadataObjects: List<*>,
                            fromConnection: AVCaptureConnection
                        ) {
                            if (scannedFlag.value) return
                            val first = didOutputMetadataObjects.firstOrNull() as? AVMetadataMachineReadableCodeObject
                                ?: return
                            val str = first.stringValue ?: return
                            scannedFlag.value = true
                            dispatch_async(dispatch_get_main_queue()) { onScanned(str) }
                        }
                    }
                    sessionHolder.delegate = delegate
                    output.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
                    output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
                }
                val previewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
                    videoGravity = AVLayerVideoGravityResizeAspectFill
                }
                view.previewLayer = previewLayer
                view.layer.addSublayer(previewLayer)
                session.startRunning()
            }
            view
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalForeignApi::class)
private class CameraPreviewView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    var previewLayer: AVCaptureVideoPreviewLayer? = null

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer?.frame = bounds
        CATransaction.commit()
    }
}

private class Holder(var value: Boolean)
private class SessionHolder {
    var session: AVCaptureSession? = null
    var delegate: NSObject? = null
}

@OptIn(ExperimentalForeignApi::class)
private fun nativeHeap_alloc_NSError(): kotlinx.cinterop.CPointer<kotlinx.cinterop.ObjCObjectVar<NSError?>>? = null
