package org.capture.a9.capture

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.capture.a9.util.FpsMeter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class Camera2CaptureEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val fpsMeter: FpsMeter,
    private val onFpsUpdate: (Float) -> Unit,
    private val onResolutionUpdate: (String) -> Unit
) {
    private val TAG = "A9CameraEngine"
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var currentCamera: Camera? = null

    fun startCapture(deviceInfo: CaptureDeviceInfo?, targetFps: Int = 90) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCamera(deviceInfo, targetFps)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get ProcessCameraProvider: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera(deviceInfo: CaptureDeviceInfo?, targetFps: Int) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = if (deviceInfo != null && deviceInfo.isExternal) {
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
                .build()
        } else if (deviceInfo != null) {
            CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { 
                        val id = (it as? androidx.camera.camera2.internal.Camera2CameraInfoImpl)?.cameraId
                        id == deviceInfo.cameraId
                    }
                }
                .build()
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Configure Preview with 1920x1200 or highest resolution
        val previewBuilder = Preview.Builder()
            .setTargetResolution(Size(1920, 1200))

        // Interop to request low latency and max framerate
        val extender = Camera2Interop.Extender(previewBuilder)
        extender.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            android.util.Range(targetFps, targetFps)
        )
        extender.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_MODE,
            android.hardware.camera2.CaptureRequest.CONTROL_MODE_AUTO
        )

        preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        // ImageAnalysis for live FPS metering and resolution reporting
        val analysisBuilder = ImageAnalysis.Builder()
            .setTargetResolution(Size(1920, 1200))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

        imageAnalysis = analysisBuilder.build().also {
            it.setAnalyzer(cameraExecutor) { imageProxy ->
                val fps = fpsMeter.onFrame()
                val width = imageProxy.width
                val height = imageProxy.height
                imageProxy.close()

                ContextCompat.getMainExecutor(context).execute {
                    onFpsUpdate(fps)
                    onResolutionUpdate("${width}x${height}")
                }
            }
        }

        try {
            currentCamera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            Log.i(TAG, "Camera bound successfully: ${deviceInfo?.name ?: "Default"}")
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed: ${e.message}")
            // Fallback to any available camera if external selector fails
            try {
                currentCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                Log.i(TAG, "Fallback to DEFAULT_BACK_CAMERA successful")
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Fallback camera binding failed: ${fallbackEx.message}")
            }
        }
    }

    fun stopCapture() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera: ${e.message}")
        }
    }

    fun release() {
        stopCapture()
        cameraExecutor.shutdown()
    }
}
