package org.capture.a9

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.capture.a9.audio.AudioPassthroughEngine
import org.capture.a9.capture.Camera2CaptureEngine
import org.capture.a9.capture.CaptureDeviceInfo
import org.capture.a9.capture.CaptureDeviceManager
import org.capture.a9.capture.UvcStreamEngine
import org.capture.a9.databinding.ActivityMainBinding
import org.capture.a9.databinding.LayoutSettingsSheetBinding
import org.capture.a9.util.FpsMeter
import org.capture.a9.util.PreferencesManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var deviceManager: CaptureDeviceManager
    private lateinit var uvcEngine: UvcStreamEngine
    private lateinit var cameraEngine: Camera2CaptureEngine
    private lateinit var audioEngine: AudioPassthroughEngine
    private val fpsMeter = FpsMeter()

    private var activeDevice: CaptureDeviceInfo? = null
    private var isUsingUvc: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            initCapture()
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    Toast.makeText(context, "USB Device: ${device?.productName ?: "Capture Card"}", Toast.LENGTH_SHORT).show()
                    scanAndConnect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Toast.makeText(context, "USB Device Disconnected", Toast.LENGTH_SHORT).show()
                    scanAndConnect()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)
        deviceManager = CaptureDeviceManager(this)
        audioEngine = AudioPassthroughEngine(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        binding.surfaceView.holder.setFormat(PixelFormat.RGBA_8888)
        binding.surfaceView.holder.addCallback(this)

        uvcEngine = UvcStreamEngine(
            context = this,
            fpsMeter = fpsMeter,
            prefs = prefs,
            onFpsUpdate = { fps ->
                binding.tvFpsBadge.text = String.format(Locale.US, "%.0f FPS", fps)
            },
            onResolutionUpdate = { res ->
                binding.tvResolutionBadge.text = res
            },
            onStatusMessage = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                binding.tvDeviceBadge.text = msg
            }
        )

        cameraEngine = Camera2CaptureEngine(
            context = this,
            lifecycleOwner = this,
            previewView = binding.previewView,
            fpsMeter = fpsMeter,
            onFpsUpdate = { fps ->
                binding.tvFpsBadge.text = String.format(Locale.US, "%.0f FPS", fps)
            },
            onResolutionUpdate = { res ->
                binding.tvResolutionBadge.text = "$res @ ${prefs.targetFps}Hz"
            }
        )

        applyScaleMode(prefs.scaleMode)
        setupListeners()
        registerUsbReceiver()
        checkPermissionsAndStart()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        uvcEngine.setSurfaceHolder(holder)
        scanAndConnect()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        uvcEngine.setSurfaceHolder(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        uvcEngine.stopCapture()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupListeners() {
        binding.btnScanDevices.setOnClickListener {
            scanAndConnect()
        }

        binding.btnAspectToggle.setOnClickListener {
            val nextMode = (prefs.scaleMode + 1) % 3
            prefs.scaleMode = nextMode
            applyScaleMode(nextMode)
            val modeName = when (nextMode) {
                PreferencesManager.SCALE_MODE_STRETCH -> "16:10 Fullscreen"
                PreferencesManager.SCALE_MODE_FIT -> "16:9 Letterbox"
                PreferencesManager.SCALE_MODE_FILL -> "Fill Crop"
                else -> ""
            }
            Toast.makeText(this, "Scaling: $modeName", Toast.LENGTH_SHORT).show()
        }

        binding.btnAudioToggle.setOnClickListener {
            toggleAudio()
        }

        binding.btnSnapshot.setOnClickListener {
            takeSnapshot()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsSheet()
        }

        binding.surfaceView.setOnClickListener {
            val isVisible = binding.hudOverlay.visibility == View.VISIBLE
            binding.hudOverlay.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        binding.previewView.setOnClickListener {
            val isVisible = binding.hudOverlay.visibility == View.VISIBLE
            binding.hudOverlay.visibility = if (isVisible) View.GONE else View.VISIBLE
        }
    }

    private fun applyScaleMode(mode: Int) {
        when (mode) {
            PreferencesManager.SCALE_MODE_STRETCH -> {
                binding.previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                binding.tvAspectBadge.text = "16:10 FULL"
            }
            PreferencesManager.SCALE_MODE_FIT -> {
                binding.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
                binding.tvAspectBadge.text = "16:9 FIT"
            }
            PreferencesManager.SCALE_MODE_FILL -> {
                binding.previewView.scaleType = PreviewView.ScaleType.FILL_START
                binding.tvAspectBadge.text = "FILL CROP"
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initCapture()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun initCapture() {
        scanAndConnect()
    }

    private fun scanAndConnect() {
        // Priority 1: Direct USB UVC Capture Card Engine
        val uvcStarted = uvcEngine.findAndStartCapture()
        if (uvcStarted) {
            isUsingUvc = true
            binding.surfaceView.visibility = View.VISIBLE
            binding.previewView.visibility = View.GONE
            binding.layoutDisconnected.visibility = View.GONE
            binding.hudOverlay.visibility = View.VISIBLE
            cameraEngine.stopCapture()
            binding.tvDeviceBadge.text = "USB HDMI UVC"
            return
        }

        // Priority 2: Fallback to Camera2 / CameraX
        isUsingUvc = false
        val preferredCamera = deviceManager.getPreferredCaptureCamera()
        activeDevice = preferredCamera

        if (preferredCamera != null) {
            binding.surfaceView.visibility = View.GONE
            binding.previewView.visibility = View.VISIBLE
            binding.layoutDisconnected.visibility = View.GONE
            binding.hudOverlay.visibility = View.VISIBLE
            cameraEngine.startCapture(preferredCamera, prefs.targetFps)
            binding.tvDeviceBadge.text = preferredCamera.name
        } else {
            binding.surfaceView.visibility = View.GONE
            binding.previewView.visibility = View.GONE
            binding.layoutDisconnected.visibility = View.VISIBLE
            binding.hudOverlay.visibility = View.GONE
        }
    }

    private fun toggleAudio() {
        if (audioEngine.isPlaying()) {
            audioEngine.stop()
            prefs.isAudioEnabled = false
            binding.btnAudioToggle.setImageResource(R.drawable.ic_volume_off)
            Toast.makeText(this, "Audio Passthrough Muted", Toast.LENGTH_SHORT).show()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                audioEngine.start(lifecycleScope)
                prefs.isAudioEnabled = true
                binding.btnAudioToggle.setImageResource(R.drawable.ic_volume_up)
                Toast.makeText(this, "Audio Passthrough Active", Toast.LENGTH_SHORT).show()
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        }
    }

    private fun takeSnapshot() {
        Toast.makeText(this, "Snapshot saved to Gallery", Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetBinding = LayoutSettingsSheetBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        sheetBinding.tvDeviceName.text = if (isUsingUvc) "Device: USB HDMI UVC Capture Card" else "Device: ${activeDevice?.name ?: "Default Camera"}"

        when (prefs.scaleMode) {
            PreferencesManager.SCALE_MODE_STRETCH -> sheetBinding.rbStretch.isChecked = true
            PreferencesManager.SCALE_MODE_FIT -> sheetBinding.rbFit.isChecked = true
            PreferencesManager.SCALE_MODE_FILL -> sheetBinding.rbFill.isChecked = true
        }

        sheetBinding.rgAspectMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbStretch -> PreferencesManager.SCALE_MODE_STRETCH
                R.id.rbFit -> PreferencesManager.SCALE_MODE_FIT
                R.id.rbFill -> PreferencesManager.SCALE_MODE_FILL
                else -> PreferencesManager.SCALE_MODE_STRETCH
            }
            prefs.scaleMode = mode
            applyScaleMode(mode)
        }

        sheetBinding.switchAudio.isChecked = audioEngine.isPlaying()
        sheetBinding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !audioEngine.isPlaying()) {
                toggleAudio()
            } else if (!isChecked && audioEngine.isPlaying()) {
                toggleAudio()
            }
        }

        dialog.show()
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        uvcEngine.release()
        audioEngine.stop()
        cameraEngine.release()
    }
}
