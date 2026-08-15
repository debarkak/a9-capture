package org.capture.a9.capture

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import org.capture.a9.util.FpsMeter
import org.capture.a9.util.PreferencesManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class UvcStreamEngine(
    private val context: Context,
    private val fpsMeter: FpsMeter,
    private val prefs: PreferencesManager,
    private val onFpsUpdate: (Float) -> Unit,
    private val onResolutionUpdate: (String) -> Unit,
    private val onStatusMessage: (String) -> Unit
) {
    private val TAG = "A9UvcEngine"
    private val ACTION_USB_PERMISSION = "org.capture.a9.USB_PERMISSION"

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var surfaceHolder: SurfaceHolder? = null

    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var videoInterface: UsbInterface? = null
    private var videoEndpoint: UsbEndpoint? = null

    private val isStreaming = AtomicBoolean(false)
    private var streamThread: Thread? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.i(TAG, "USB Permission granted for device: ${it.productName}")
                            startUvcCapture(it)
                        }
                    } else {
                        Log.w(TAG, "USB Permission denied for device: ${device?.productName}")
                        onStatusMessage("USB Permission Denied")
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }
    }

    fun setSurfaceHolder(holder: SurfaceHolder) {
        this.surfaceHolder = holder
    }

    fun findAndStartCapture(): Boolean {
        if (isStreaming.get()) {
            return true
        }

        for (device in usbManager.deviceList.values) {
            if (isUvcDevice(device)) {
                usbDevice = device
                if (usbManager.hasPermission(device)) {
                    Log.i(TAG, "Already have permission for ${device.productName}, starting stream...")
                    startUvcCapture(device)
                } else {
                    Log.i(TAG, "Requesting USB permission for ${device.productName}...")
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                    val permissionIntent = PendingIntent.getBroadcast(
                        context, 0, Intent(ACTION_USB_PERMISSION), flags
                    )
                    usbManager.requestPermission(device, permissionIntent)
                }
                return true
            }
        }
        return false
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO || device.deviceClass == 239) return true
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                return true
            }
        }
        return false
    }

    private fun startUvcCapture(device: UsbDevice) {
        stopCapture()

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "Failed to open UsbDeviceConnection")
            onStatusMessage("Could not open USB connection")
            return
        }
        usbConnection = connection

        var streamingIntf: UsbInterface? = null
        var inEndpoint: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO && intf.interfaceSubclass == 2) {
                streamingIntf = intf
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        inEndpoint = ep
                        break
                    }
                }
                if (inEndpoint != null) break
            }
        }

        if (inEndpoint == null) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        streamingIntf = intf
                        inEndpoint = ep
                        break
                    }
                }
                if (inEndpoint != null) break
            }
        }

        if (streamingIntf == null || inEndpoint == null) {
            Log.e(TAG, "Could not find UVC video streaming endpoint")
            onStatusMessage("No video stream endpoint found")
            return
        }

        videoInterface = streamingIntf
        videoEndpoint = inEndpoint

        val claimed = connection.claimInterface(streamingIntf, true)
        Log.i(TAG, "Claimed video interface ${streamingIntf.id}: $claimed, endpoint: ${inEndpoint.address} (${inEndpoint.maxPacketSize} bytes)")

        sendUvcProbeCommit(connection, streamingIntf.id)

        isStreaming.set(true)
        streamThread = Thread({ readStreamLoop(connection, inEndpoint) }, "A9-UVC-Stream").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        onStatusMessage("Connected: ${device.productName ?: "HDMI Capture"}")
    }

    private fun sendUvcProbeCommit(connection: UsbDeviceConnection, interfaceId: Int) {
        try {
            val probeData = ByteArray(26)
            connection.controlTransfer(0xA1, 0x81, 0x0100, interfaceId, probeData, probeData.size, 1000)

            val interval60 = 166666
            probeData[0] = 0x01.toByte()
            probeData[2] = 0x01.toByte()
            probeData[3] = 0x01.toByte()
            probeData[4] = (interval60 and 0xFF).toByte()
            probeData[5] = ((interval60 shr 8) and 0xFF).toByte()
            probeData[6] = ((interval60 shr 16) and 0xFF).toByte()
            probeData[7] = ((interval60 shr 24) and 0xFF).toByte()

            connection.controlTransfer(0x21, 0x01, 0x0100, interfaceId, probeData, probeData.size, 1000)
            connection.controlTransfer(0x21, 0x01, 0x0200, interfaceId, probeData, probeData.size, 1000)
            Log.i(TAG, "UVC 60 FPS Probe/Commit sent successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Probe/Commit negotiation warning: ${e.message}")
        }
    }

    private fun readStreamLoop(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        val readBufferSize = 64 * 1024
        val rawBuffer = ByteArray(readBufferSize)
        val frameBuffer = ByteArrayOutputStream(2 * 1024 * 1024)

        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }

        var frameWidth = 1920
        var frameHeight = 1080
        var lastFid = -1
        var inJpeg = false

        while (isStreaming.get()) {
            val bytesRead = connection.bulkTransfer(endpoint, rawBuffer, rawBuffer.size, 100)
            if (bytesRead > 0) {
                val headerLen = rawBuffer[0].toInt() and 0xFF
                if (headerLen in 2..12 && headerLen <= bytesRead) {
                    val headerInfo = rawBuffer[1].toInt() and 0xFF
                    val fid = headerInfo and 0x01
                    val isEof = (headerInfo and 0x02) != 0
                    val isErr = (headerInfo and 0x40) != 0

                    if (isErr) {
                        frameBuffer.reset()
                        inJpeg = false
                        lastFid = fid
                        continue
                    }

                    val payloadLen = bytesRead - headerLen
                    if (payloadLen > 0) {
                        val offset = headerLen
                        // Check for SOI marker
                        if (!inJpeg) {
                            for (i in offset until bytesRead - 1) {
                                if ((rawBuffer[i].toInt() and 0xFF) == 0xFF && (rawBuffer[i + 1].toInt() and 0xFF) == 0xD8) {
                                    inJpeg = true
                                    frameBuffer.reset()
                                    frameBuffer.write(rawBuffer, i, bytesRead - i)
                                    break
                                }
                            }
                        } else {
                            frameBuffer.write(rawBuffer, offset, payloadLen)
                        }

                        // Check for complete frame (EOF bit or EOI marker 0xFF 0xD9)
                        if (inJpeg && (isEof || (lastFid != -1 && fid != lastFid))) {
                            val jpegBytes = frameBuffer.toByteArray()
                            if (jpegBytes.size > 8192) {
                                decodeAndRender(jpegBytes, opts) { w, h, fps ->
                                    frameWidth = w
                                    frameHeight = h
                                    android.os.Handler(context.mainLooper).post {
                                        onFpsUpdate(fps)
                                        onResolutionUpdate("${frameWidth}x${frameHeight} @ 60Hz")
                                    }
                                }
                            }
                            frameBuffer.reset()
                            inJpeg = false
                        }
                    }
                    lastFid = fid
                }
            }
        }
    }

    private fun decodeAndRender(
        rawBytes: ByteArray,
        opts: BitmapFactory.Options,
        onStats: (Int, Int, Float) -> Unit
    ) {
        val bitmap = try {
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
        } catch (e: Exception) {
            null
        }

        if (bitmap != null) {
            val fps = fpsMeter.onFrame()
            renderFrameToSurface(bitmap)
            onStats(bitmap.width, bitmap.height, fps)
        }
    }

    private fun renderFrameToSurface(bitmap: Bitmap) {
        val holder = surfaceHolder ?: return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockHardwareCanvas()
            if (canvas != null) {
                val surfaceWidth = canvas.width
                val surfaceHeight = canvas.height

                when (prefs.scaleMode) {
                    PreferencesManager.SCALE_MODE_STRETCH -> {
                        // 16:10 Full stretch (Tab A9+ 1920x1200)
                        val dstRect = Rect(0, 0, surfaceWidth, surfaceHeight)
                        canvas.drawBitmap(bitmap, null, dstRect, paint)
                    }
                    PreferencesManager.SCALE_MODE_FIT -> {
                        // 16:9 Letterbox
                        canvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
                        val scale = minOf(surfaceWidth.toFloat() / bitmap.width, surfaceHeight.toFloat() / bitmap.height)
                        val dstW = (bitmap.width * scale).toInt()
                        val dstH = (bitmap.height * scale).toInt()
                        val left = (surfaceWidth - dstW) / 2
                        val top = (surfaceHeight - dstH) / 2
                        val dstRect = Rect(left, top, left + dstW, top + dstH)
                        canvas.drawBitmap(bitmap, null, dstRect, paint)
                    }
                    PreferencesManager.SCALE_MODE_FILL -> {
                        // Fill Crop
                        val scale = maxOf(surfaceWidth.toFloat() / bitmap.width, surfaceHeight.toFloat() / bitmap.height)
                        val dstW = (bitmap.width * scale).toInt()
                        val dstH = (bitmap.height * scale).toInt()
                        val left = (surfaceWidth - dstW) / 2
                        val top = (surfaceHeight - dstH) / 2
                        val dstRect = Rect(left, top, left + dstW, top + dstH)
                        canvas.drawBitmap(bitmap, null, dstRect, paint)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame render error: ${e.message}")
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    // Surface destroyed
                }
            }
        }
    }

    fun stopCapture() {
        isStreaming.set(false)
        streamThread?.interrupt()
        streamThread = null

        videoInterface?.let {
            try {
                usbConnection?.releaseInterface(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing USB interface: ${e.message}")
            }
        }
        videoInterface = null
        videoEndpoint = null

        usbConnection?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing USB connection: ${e.message}")
            }
        }
        usbConnection = null
    }

    fun release() {
        stopCapture()
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}
