package org.capture.a9.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

data class CaptureDeviceInfo(
    val cameraId: String,
    val isExternal: Boolean,
    val name: String,
    val maxResolution: Pair<Int, Int>?,
    val supportedFps: List<Int>
)

class CaptureDeviceManager(private val context: Context) {
    private val TAG = "A9CaptureDeviceMgr"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun getAttachedUsbCaptureDevices(): List<UsbDevice> {
        val list = mutableListOf<UsbDevice>()
        for (device in usbManager.deviceList.values) {
            // Check for UVC (Video Interface Class 0x0E / 14 or Miscellaneous / Audio)
            var isVideo = false
            if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO || device.deviceClass == 239) {
                isVideo = true
            } else {
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                        isVideo = true
                        break
                    }
                }
            }
            if (isVideo) {
                list.add(device)
            }
        }
        return list
    }

    fun getAvailableCaptureCameras(): List<CaptureDeviceInfo> {
        val devices = mutableListOf<CaptureDeviceInfo>()
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val isExternal = (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888) 
                    ?: map?.getOutputSizes(android.view.SurfaceHolder::class.java)
                
                val maxRes = sizes?.maxByOrNull { it.width * it.height }?.let { Pair(it.width, it.height) }
                
                val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val fpsList = fpsRanges?.map { it.upper }?.distinct()?.sortedDescending() ?: listOf(30, 60, 90)

                val name = if (isExternal) "USB HDMI Capture Card ($id)" else "Camera $id"
                devices.add(CaptureDeviceInfo(id, isExternal, name, maxRes, fpsList))
                Log.d(TAG, "Found camera: $id (external=$isExternal, maxRes=$maxRes, fps=$fpsList)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying camera characteristics: ${e.message}")
        }
        return devices
    }

    fun getPreferredCaptureCamera(): CaptureDeviceInfo? {
        val all = getAvailableCaptureCameras()
        // Prioritize external USB capture card
        return all.firstOrNull { it.isExternal } ?: all.firstOrNull()
    }
}
