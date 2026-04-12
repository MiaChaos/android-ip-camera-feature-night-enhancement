package com.github.digitallyrefined.androidipcamera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.helpers.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class StreamingService : LifecycleService() {

    private val binder = LocalBinder()
    var streamingServerHelper: StreamingServerHelper? = null
    private var cameraExecutor: ExecutorService? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var lastFrameTime = 0L
    private var zoomFocusX = 0.5f
    private var zoomFocusY = 0.5f
    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraResolutionHelper: CameraResolutionHelper? = null
    private var backLenses: List<Pair<String, String>> = emptyList()
    private var lastSnapshot: ByteArray? = null
    private var imageCapture: ImageCapture? = null

    private val onPrefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key) {
            "camera_resolution", "camera_zoom", "camera_exposure", "camera_facing" -> {
                lifecycleScope.launch(Dispatchers.Main) {
                    startCamera()
                }
            }
            "camera_zoom_focus_x" -> {
                zoomFocusX = sharedPreferences.getFloat(key, 0.5f)
            }
            "camera_zoom_focus_y" -> {
                zoomFocusY = sharedPreferences.getFloat(key, 0.5f)
            }
        }
    }

    // UI Callbacks
    var onClientConnected: (() -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onCameraRestartNeeded: (() -> Unit)? = null
    var onScreenBlackChanged: ((Boolean) -> Unit)? = null

    // Preview surface provider
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    private val notificationChannelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (intent?.action == NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED) {
                    val channelId = intent.getStringExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_ID)
                    if (channelId == CHANNEL_ID) {
                        val manager = getSystemService(NotificationManager::class.java)
                        val channel = manager.getNotificationChannel(CHANNEL_ID)
                        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                            Log.w(TAG, "Notification channel $channelId blocked by user. Stopping service.")
                            handleStopService()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "StreamingService"
        private const val STREAM_PORT = 4444
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "streaming_service_channel"
        private const val PREF_LAST_CAMERA_FACING = "last_camera_facing"
        private const val CAMERA_FACING_BACK = "back"
        private const val CAMERA_FACING_FRONT = "front"
        const val ACTION_STOP_SERVICE = "com.github.digitallyrefined.androidipcamera.STOP_SERVICE"
        const val ACTION_RESTART_NOTIFICATION = "com.github.digitallyrefined.androidipcamera.RESTART_NOTIFICATION"
    }

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            handleStopService()
            return START_NOT_STICKY
        } else if (intent?.action == ACTION_RESTART_NOTIFICATION) {
            startForegroundService()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleStopService() {
        val closeIntent = Intent("com.github.digitallyrefined.androidipcamera.CLOSE_APP")
        closeIntent.setPackage(packageName) // Ensure only our app receives this
        sendBroadcast(closeIntent)

        stopForeground(true)
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        startForegroundService()

        // Load saved camera facing
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        
        // Initialize cached values and register listener
        zoomFocusX = prefs.getFloat("camera_zoom_focus_x", 0.5f)
        zoomFocusY = prefs.getFloat("camera_zoom_focus_y", 0.5f)
        prefs.registerOnSharedPreferenceChangeListener(onPrefChangeListener)

        lensFacing = if (prefs.getString(PREF_LAST_CAMERA_FACING, CAMERA_FACING_BACK) == CAMERA_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val filter = IntentFilter(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
            registerReceiver(notificationChannelReceiver, filter)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startNotificationChannelCheckFallback()
        }

        // Pre-detect camera capabilities (like flash strength) so UI can show it early
        val initialLensId = if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA) prefs.getString("camera_lens_id", null) else null
        detectCameraCapabilities(initialLensId)
        detectBackLenses()
    }

    fun triggerAutoFocus(x: Float = 0.5f, y: Float = 0.5f) {
        val cam = camera ?: return
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                val point = factory.createPoint(x, y)
                
                // 1. Temporarily clear manual overrides to allow AF/AE to work
                val camera2Control = Camera2CameraControl.from(cam.cameraControl)
                camera2Control.captureRequestOptions = CaptureRequestOptions.Builder().build()
                
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                Log.i(TAG, "Force Auto Focus at ($x, $y): Triggering AF/AE...")
                val result = cam.cameraControl.startFocusAndMetering(action)
                
                // 2. Once the action is triggered (or after a short delay), restore manual settings
                // We use a listener to ensure we don't fight with the metering action immediately
                result.addListener({
                    Log.i(TAG, "Force Auto Focus at ($x, $y): Action completed, restoring manual settings")
                    applyCamera2InteropSettings()
                }, ContextCompat.getMainExecutor(this@StreamingService))
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger auto focus at ($x, $y): ${e.message}")
                applyCamera2InteropSettings() // Attempt to restore even on error
            }
        }
    }

    private fun detectBackLenses() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val lenses = mutableListOf<Pair<String, String>>()
            cameraManager.cameraIdList.forEach { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val label = when {
                        focalLengths == null -> "Camera $id"
                        focalLengths.any { it < 3.0f } -> "Ultra Wide ($id)"
                        focalLengths.any { it > 6.0f } -> "Telephoto ($id)"
                        else -> "Main Camera ($id)"
                    }
                    lenses.add(id to label)
                } else if (facing == null) {
                    // Some external or special lenses might have null facing, check them too
                    lenses.add(id to "External/Special ($id)")
                }
            }
            backLenses = lenses
            Log.i(TAG, "Detected lenses: $backLenses")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detect lenses", e)
        }
    }

    private fun detectCameraCapabilities(specificId: String? = null) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = specificId ?: getCameraId(cameraManager)
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)

            // Hardware Support Level
            val hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val levelStr = when (hwLevel) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                else -> "UNKNOWN ($hwLevel)"
            }
            Log.i(TAG, "Hardware Support Level for Camera $cameraId: $levelStr")

            // Manual Sensor Support
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val hasManualSensor = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) ?: false
            Log.i(TAG, "Camera $cameraId Manual Sensor Support: $hasManualSensor")

            // Flash strength (API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val flashInfoStrengthMaxLevelField = CameraCharacteristics::class.java.getDeclaredField("FLASH_INFO_STRENGTH_MAXIMUM_LEVEL")
                    @Suppress("UNCHECKED_CAST")
                    val flashInfoStrengthMaxLevelKey = flashInfoStrengthMaxLevelField.get(null) as CameraCharacteristics.Key<Int>
                    
                    val maxLevel = characteristics.get(flashInfoStrengthMaxLevelKey) ?: 1
                    prefs.edit().putInt("camera_torch_max_level", maxLevel).apply()
                    Log.i(TAG, "Pre-detected max torch level: $maxLevel")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to pre-detect torch capabilities: ${e.message}")
                }
            }

            // Focus distance
            val minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            var finalMinFocus = minFocusDistance
            
            // On some devices, back cameras might report 0 but actually support focus
            if (finalMinFocus == 0f && lensFacing == CameraSelector.DEFAULT_BACK_CAMERA && cameraId == "0") {
                finalMinFocus = 10f // Default fallback for main back camera
            }
            
            prefs.edit().putFloat("camera_min_focus_distance", finalMinFocus).apply()
            Log.i(TAG, "Pre-detected min focus distance for $cameraId: $finalMinFocus (Raw: $minFocusDistance)")

            // ISO range
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            if (isoRange != null) {
                prefs.edit().putInt("camera_iso_min", isoRange.lower).putInt("camera_iso_max", isoRange.upper).apply()
                Log.i(TAG, "Pre-detected ISO range: ${isoRange.lower} - ${isoRange.upper}")
            }

            // Exposure time (Shutter) range
            val shutterRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            if (shutterRange != null) {
                prefs.edit().putLong("camera_shutter_min", shutterRange.lower).putLong("camera_shutter_max", shutterRange.upper).apply()
                Log.i(TAG, "Pre-detected Shutter range: ${shutterRange.lower} - ${shutterRange.upper} ns")
            }

            // FPS range
            val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            if (fpsRanges != null && fpsRanges.isNotEmpty()) {
                var minFps = 1000
                var maxFps = 0
                fpsRanges.forEach { range ->
                    if (range.lower < minFps) minFps = range.lower
                    if (range.upper > maxFps) maxFps = range.upper
                }
                // Save the absolute min/max supported by this sensor
                prefs.edit().putInt("camera_fps_min", minFps).putInt("camera_fps_max", maxFps).apply()
                Log.i(TAG, "Pre-detected FPS range: $minFps - $maxFps")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detect camera capabilities", e)
        }
    }

    private fun startNotificationChannelCheckFallback() {
        lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val manager = getSystemService(NotificationManager::class.java)
                val channel = manager.getNotificationChannel(CHANNEL_ID)
                if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                    Log.w(TAG, "Notification channel $CHANNEL_ID blocked (fallback check). Stopping service.")
                    handleStopService()
                    break
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(onPrefChangeListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                unregisterReceiver(notificationChannelReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering notification receiver: ${e.message}")
            }
        }
        cameraExecutor?.shutdown()
        stopCamera()
        lifecycleScope.launch(Dispatchers.IO) {
            streamingServerHelper?.stopStreamingServer()
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Streaming Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, com.github.digitallyrefined.androidipcamera.activities.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val restartIntent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_RESTART_NOTIFICATION
        }
        val restartPendingIntent = PendingIntent.getService(this, 2, restartIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android IP Camera Streaming")
            .setContentText("Camera server is running in background")
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification, "Exit App", stopPendingIntent)
            .setDeleteIntent(restartPendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun setPreviewSurface(surfaceProvider: Preview.SurfaceProvider?) {
        currentSurfaceProvider = surfaceProvider
        if (isCameraRunning() && surfaceProvider != null) {
            // Re-bind to include preview if needed, or update preview
            // CameraX is tricky with dynamic surface updates.
            // Usually we just need to restart camera to attach new preview.
            startCamera()
        } else if (isCameraRunning() && surfaceProvider == null) {
            // If surface is null (backgrounded), we might want to unbind preview to save resources,
            // or just let it be (CameraX handles detached surfaces).
            // However, to be safe and ensure background streaming works, we should keep the camera running
            // but maybe without the Preview use case if it causes issues.
            // For now, let's just restart camera without preview if surface is null?
            // Actually, if we just unbindAll and rebind with/without Preview, that works.
            startCamera()
        }
    }

    fun isCameraRunning(): Boolean {
        return camera != null
    }

    fun getLocalIpAddress(): String {
        try {
            NetworkInterface.getNetworkInterfaces().toList().forEach { networkInterface ->
                networkInterface.inetAddresses.toList().forEach { address ->
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "unknown"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "unknown"
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit()
            .putString(
                PREF_LAST_CAMERA_FACING,
                if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA) CAMERA_FACING_FRONT else CAMERA_FACING_BACK
            )
            .apply()
        cameraResolutionHelper = null
        if (streamingServerHelper?.getClients()?.isNotEmpty() == true) {
            startCamera()
        }
    }

    fun startStreamingServer() {
        try {
            // Initialize Default Certificate Logic
            val secureStorage = SecureStorage(this)
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)

            if (CertificateHelper.certificateExists(this)) {
                val existingCertPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, null)
                if (existingCertPassword.isNullOrEmpty()) {
                    val certFile = File(filesDir, "personal_certificate.p12")
                    if (certFile.exists()) certFile.delete()
                    generateCertificateAndStart()
                } else {
                    initServer()
                }
            } else {
                generateCertificateAndStart()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting server: ${e.message}")
        }
    }

    private fun generateCertificateAndStart() {
        val randomPassword = generateRandomPassword()
        lifecycleScope.launch(Dispatchers.IO) {
            val certFile = CertificateHelper.generateCertificate(this@StreamingService, randomPassword)
            if (certFile != null) {
                val secureStorage = SecureStorage(this@StreamingService)
                secureStorage.putSecureString(SecureStorage.KEY_CERT_PASSWORD, randomPassword)
                PreferenceManager.getDefaultSharedPreferences(this@StreamingService).edit().remove("certificate_path").apply()
                kotlinx.coroutines.delay(100)
                initServer()
                launch(Dispatchers.Main) {
                    Toast.makeText(this@StreamingService, "Certificate generated", Toast.LENGTH_SHORT).show()
                }
            } else {
                launch(Dispatchers.Main) {
                    Toast.makeText(this@StreamingService, "Failed to generate certificate", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun initServer() {
        if (streamingServerHelper == null) {
            streamingServerHelper = StreamingServerHelper(
                this,
                onLog = { message ->
                    Log.i(TAG, "StreamingServer: $message")
                    onLog?.invoke(message)
                },
                onClientConnected = {
                    launchMain {
                        onClientConnected?.invoke()
                        startCameraIfNeeded()
                    }
                },
                onClientDisconnected = {
                    if (streamingServerHelper?.getClients()?.isEmpty() == true) {
                        launchMain {
                            stopCamera()
                            onClientDisconnected?.invoke()
                        }
                    }
                },
                onControlCommand = { key: String, value: String -> handleRemoteControl(key, value) }
            )
        }
        streamingServerHelper?.startStreamingServer()
        Log.i(TAG, "Requested HTTPS server start on port $STREAM_PORT")
    }

    private fun launchMain(block: () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) {
            block()
        }
    }

    private fun startCameraIfNeeded() {
        if (!allPermissionsGranted() || isCameraRunning()) return
        startCamera()
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        imageAnalyzer = null
        camera = null
    }

    fun getBackLenses(): List<Pair<String, String>> = backLenses

    fun getLatestSnapshot(): ByteArray? = lastSnapshot

    fun getLensPrefKey(base: String): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val selectedLensId = prefs.getString("camera_lens_id", "default") ?: "default"
        val facing = if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA) "front" else "back"
        return "${base}_${facing}_${selectedLensId}"
    }

    /**
     * Performs 2x2 Software Pixel Binning on raw NV21 data.
     * This reduces resolution by half in both dimensions but increases SNR by averaging pixels.
     */
    private fun performSoftwareBinning(data: ByteArray, width: Int, height: Int): Triple<ByteArray, Int, Int> {
        val newWidth = width / 2
        val newHeight = height / 2
        val binnedSize = newWidth * newHeight * 3 / 2
        val binnedData = ByteArray(binnedSize)

        // 1. Bin the Y (Luminance) plane
        // Average 2x2 blocks: (P[x,y] + P[x+1,y] + P[x,y+1] + P[x+1,y+1]) / 4
        for (y in 0 until newHeight) {
            val srcRow1 = (y * 2) * width
            val srcRow2 = (y * 2 + 1) * width
            val dstRow = y * newWidth
            for (x in 0 until newWidth) {
                val sum = (data[srcRow1 + x * 2].toInt() and 0xFF) +
                          (data[srcRow1 + x * 2 + 1].toInt() and 0xFF) +
                          (data[srcRow2 + x * 2].toInt() and 0xFF) +
                          (data[srcRow2 + x * 2 + 1].toInt() and 0xFF)
                binnedData[dstRow + x] = (sum / 4).toByte()
            }
        }

        // 2. Bin the UV (Chrominance) plane
        // In NV21, UV data starts after Y plane. UV is already 2x2 subsampled for 4:2:0.
        // For a 2x2 binning, we can either subsample or average UV. 
        // Simple subsampling is often sufficient and faster for UV.
        val ySize = width * height
        val binnedYSize = newWidth * newHeight
        val uvHeight = height / 2
        val uvWidth = width // UV plane has interleaved U/V pairs, width is same as Y
        
        for (y in 0 until (newHeight / 2)) {
            val srcRow = ySize + (y * 2) * uvWidth
            val dstRow = binnedYSize + y * newWidth
            for (x in 0 until (newWidth / 2)) {
                // Keep the V,U pair
                binnedData[dstRow + x * 2] = data[srcRow + x * 4]         // V
                binnedData[dstRow + x * 2 + 1] = data[srcRow + x * 4 + 1] // U
            }
        }

        return Triple(binnedData, newWidth, newHeight)
    }

    fun takeHighResSnapshot(callback: (ByteArray?) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            callback(lastSnapshot)
            return
        }

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        // This is raw JPEG from the sensor
                        lastSnapshot = bytes
                        callback(bytes)
                    } catch (e: Exception) {
                        Log.e(TAG, "High-res capture error: ${e.message}")
                        callback(lastSnapshot)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "High-res capture failed: ${exception.message}")
                    callback(lastSnapshot)
                }
            }
        )
    }

    fun getDeviceStatus(): Map<String, Any> {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = (level / scale.toFloat() * 100).toInt()
        
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
        
        // Temperature is in tenths of a degree Celsius
        val temp = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val minFocusDistance = prefs.getFloat("camera_min_focus_distance", 0f)
        val isoMin = prefs.getInt("camera_iso_min", 100)
        val isoMax = prefs.getInt("camera_iso_max", 3200)
        val shutterMin = prefs.getLong("camera_shutter_min", 100000L)
        val shutterMax = prefs.getLong("camera_shutter_max", 1000000000L)
        
        // Manual Support Check
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = getCameraId(cameraManager)
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val hasManualSensor = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) ?: false
        
        // Current values for the selected lens
        val curISO = prefs.getInt(getLensPrefKey("camera_iso"), 400)
        val curShutter = prefs.getLong(getLensPrefKey("camera_shutter"), 20000000L)
        val curFocusDistance = prefs.getFloat(getLensPrefKey("camera_focus_distance"), 0f)
        val curFocusMode = prefs.getString(getLensPrefKey("camera_focus_mode"), "auto") ?: "auto"
        val curExposureMode = prefs.getString(getLensPrefKey("camera_exposure_mode"), "auto") ?: "auto"
        val curBinning = prefs.getBoolean(getLensPrefKey("camera_pixel_binning"), false)
        
        return mapOf(
            "battery" to batteryPct,
            "isCharging" to isCharging,
            "temp" to temp,
            "uptime" to (SystemClock.elapsedRealtime() / 1000), // seconds
            "power_saving" to prefs.getBoolean("power_saving_mode", false),
            "screen_black" to prefs.getBoolean("screen_black_mode", false),
            "minFocusDistance" to minFocusDistance,
            "isoMin" to isoMin,
            "isoMax" to isoMax,
            "shutterMin" to shutterMin,
            "shutterMax" to shutterMax,
            "hasManualSensor" to hasManualSensor,
            "curISO" to curISO,
            "curShutter" to curShutter,
            "curFocusDistance" to curFocusDistance,
            "curFocusMode" to curFocusMode,
            "curExposureMode" to curExposureMode,
            "curBinning" to curBinning,
            "curZoom" to (prefs.getString(getLensPrefKey("camera_zoom"), "1.0") ?: "1.0"),
            "curSoftwareZoom" to (prefs.getString(getLensPrefKey("software_zoom"), "1.0") ?: "1.0")
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            this.cameraProvider = cameraProvider

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val selectedLensId = prefs.getString("camera_lens_id", null)

            // Determine lens facing and selector
            val cameraSelector = if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else if (!selectedLensId.isNullOrEmpty()) {
                CameraSelector.Builder()
                    .addCameraFilter { cameras ->
                        cameras.filter { camInfo ->
                            androidx.camera.camera2.interop.Camera2CameraInfo.from(camInfo).cameraId == selectedLensId
                        }
                    }
                    .build()
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            // Pre-detect camera capabilities (like flash strength, focus distance) so UI can show it early
            detectCameraCapabilities(if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA) selectedLensId else null)

            // Initialize camera resolution helper
            if (cameraResolutionHelper == null) {
                cameraResolutionHelper = CameraResolutionHelper(this)
            }
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = if (!selectedLensId.isNullOrEmpty() && lensFacing == CameraSelector.DEFAULT_BACK_CAMERA) {
                selectedLensId
            } else {
                getCameraId(cameraManager)
            }
            cameraResolutionHelper?.initializeResolutions(cameraId)

            // Image Analysis (Streaming)
            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .apply {
                    val isPowerSaving = prefs.getBoolean("power_saving_mode", false)
                    val quality = if (isPowerSaving) "240p" else (prefs.getString(getLensPrefKey("camera_resolution"), "low") ?: "low")
                    val targetResolution = cameraResolutionHelper?.getResolutionForQuality(quality)

                    if (targetResolution != null) {
                        Log.i(TAG, "Requesting resolution: $quality -> ${targetResolution.width}x${targetResolution.height}")
                        if (isPowerSaving) Log.i(TAG, "Power Saving Mode active")
                        setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(ResolutionStrategy(targetResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                .build()
                        )
                    } else {
                        // Fallback
                         val fallbackResolution = when (quality) {
                            "1080p", "high" -> Size(1920, 1080)
                            "720p", "medium" -> Size(1280, 720)
                            "480p", "low" -> Size(640, 480)
                            "240p" -> Size(320, 240)
                            else -> Size(640, 480)
                        }
                        Log.i(TAG, "Target resolution not found, using fallback: $quality -> ${fallbackResolution.width}x${fallbackResolution.height}")
                        setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(ResolutionStrategy(fallbackResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                .build()
                        )
                    }
                }
                .build()
                .also { analysis ->
                    cameraExecutor?.let { executor ->
                        analysis.setAnalyzer(executor) { image ->
                            if (streamingServerHelper?.getClients()?.isNotEmpty() == true) {
                                processImage(image)
                            }
                            image.close()
                        }
                    }
                }

            // High Resolution Image Capture
            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            
            // Try to set highest possible resolution for ImageCapture
            cameraResolutionHelper?.highResolution?.let { highRes ->
                // For ImageCapture, we actually want even higher than what we use for streaming if available
                // But let's at least ensure it's not restricted by the same low/medium/high logic
                // CameraX by default picks the largest resolution for ImageCapture.
            }

            imageCapture = captureBuilder.build()

            try {
                cameraProvider.unbindAll()

                // Build Use Cases
                val useCases = mutableListOf<androidx.camera.core.UseCase>(imageAnalyzer!!, imageCapture!!)

                // Add Preview if surface provider is available
                if (currentSurfaceProvider != null) {
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(currentSurfaceProvider)
                    useCases.add(preview)
                }

                // Bind to Service Lifecycle
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    *useCases.toTypedArray()
                )

                // Apply initial settings (Torch, Zoom, etc)
                applyCameraSettings()

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getCameraId(cameraManager: CameraManager): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val selectedLensId = prefs.getString("camera_lens_id", null)

        return when (lensFacing) {
            CameraSelector.DEFAULT_BACK_CAMERA -> {
                if (!selectedLensId.isNullOrEmpty()) {
                    selectedLensId
                } else {
                    cameraManager.cameraIdList.find { id ->
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    } ?: "0"
                }
            }
            CameraSelector.DEFAULT_FRONT_CAMERA -> {
                cameraManager.cameraIdList.find { id ->
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                } ?: "1"
            }
            else -> "0"
        }
    }

    private fun setTorchEnabled(enabled: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // Find a camera ID that actually has a flash unit
            val flashCameraId = cameraManager.cameraIdList.find { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: "0"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager.setTorchMode(flashCameraId, enabled)
                Log.d(TAG, "Global torch set to $enabled on camera $flashCameraId")
            } else {
                camera?.cameraControl?.enableTorch(enabled)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set torch mode", e)
            // Fallback to CameraX control if global control fails
            camera?.cameraControl?.enableTorch(enabled)
        }
    }

    private fun applyCameraSettings() {
        val cam = camera ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Torch / Flash (Basic CameraX API)
        try {
            val torchPref = prefs.getString("camera_torch", "off") ?: "off"
            val isTorchOn = torchPref == "on"
            setTorchEnabled(isTorchOn)
            imageCapture?.flashMode = if (isTorchOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        } catch (e: Exception) { 
            Log.e(TAG, "Torch basic error: ${e.message}") 
        }

        // Zoom (Basic CameraX API)
        val requestedZoomFactor = prefs.getString(getLensPrefKey("camera_zoom"), "1.0")?.toFloatOrNull() ?: 1.0f
        cam.cameraControl.setZoomRatio(requestedZoomFactor)

        // Exposure Compensation (Basic CameraX API)
        val exposureValue = prefs.getString(getLensPrefKey("camera_exposure"), "0")?.toIntOrNull() ?: 0
        cam.cameraControl.setExposureCompensationIndex(exposureValue)

        // Apply Unified Camera2 Interop Settings (Focus, ISO, Shutter, Torch Strength)
        applyCamera2InteropSettings()
    }

    private fun applyCamera2InteropSettings() {
        val cam = camera ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        
        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()

            // 1. Manual Focus
            val focusMode = prefs.getString(getLensPrefKey("camera_focus_mode"), "auto") ?: "auto"
            if (focusMode == "manual") {
                val distance = prefs.getFloat(getLensPrefKey("camera_focus_distance"), 0f)
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                Log.d(TAG, "Manual Focus distance set: $distance")
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            // 3. FPS Control & Shutter Linkage
            val targetFps = prefs.getInt(getLensPrefKey("camera_fps"), 30)
            val shutterNs = prefs.getLong(getLensPrefKey("camera_shutter"), 20000000L)
            val exposureMode = prefs.getString(getLensPrefKey("camera_exposure_mode"), "auto") ?: "auto"
            
            // Linkage: If shutter is longer than 1/fps, we MUST reduce FPS
            val maxFpsByShutter = (1000000000L / Math.max(1, shutterNs)).toInt()
            val effectiveFps = if (exposureMode == "manual") {
                Math.min(targetFps, maxFpsByShutter)
            } else {
                targetFps
            }
            
            // Set AE Target FPS Range (Locked range for fixed FPS, or flexible for auto)
            val fpsRange = android.util.Range(effectiveFps, effectiveFps)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            
            // 4. Manual Exposure (ISO/Shutter)
            if (exposureMode == "manual") {
                val iso = prefs.getInt(getLensPrefKey("camera_iso"), 400)
                val shutterNs = prefs.getLong(getLensPrefKey("camera_shutter"), 20000000L)
                
                // For manual exposure, we must turn off AE
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
                
                // Some devices require CONTROL_MODE to be MANUAL or OFF when overriding AE/AF
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                
                Log.d(TAG, "Manual Exposure set: ISO=$iso, Shutter=$shutterNs ns")
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            // 3. Torch Strength (API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val flashStrengthLevelField = CaptureRequest::class.java.getDeclaredField("FLASH_STRENGTH_LEVEL")
                    @Suppress("UNCHECKED_CAST")
                    val flashStrengthLevelKey = flashStrengthLevelField.get(null) as CaptureRequest.Key<Int>
                    
                    val strength = prefs.getString(getLensPrefKey("camera_torch_strength"), "1")?.toIntOrNull() ?: 1
                    builder.setCaptureRequestOption(flashStrengthLevelKey, strength)
                    
                    val torchPref = prefs.getString("camera_torch", "off")
                    if (torchPref == "on") {
                        builder.setCaptureRequestOption(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    }
                } catch (_: Exception) {}
            }

            camera2Control.captureRequestOptions = builder.build()
            Log.i(TAG, "Applied unified Camera2 settings: Focus=$focusMode, Exposure=$exposureMode")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply Camera2 interop settings: ${e.message}")
        }
    }

    private fun setExposureManual(iso: Int, shutterNs: Long) {
        applyCamera2InteropSettings()
    }

    private fun setFocusManual(distance: Float) {
        applyCamera2InteropSettings()
    }

    private fun setTorchStrength(level: Int) {
        applyCamera2InteropSettings()
    }

    private fun handleRemoteControl(key: String, value: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val cam = camera

        when (key) {
            "torch" -> {
                val next = when (value.lowercase()) {
                    "on" -> "on"
                    "off" -> "off"
                    "toggle" -> {
                        val current = prefs.getString("camera_torch", "off") ?: "off"
                        if (current == "on") "off" else "on"
                    }
                    else -> return
                }
                prefs.edit().putString("camera_torch", next).apply()
                launchMain {
                    setTorchEnabled(next == "on")
                }
            }
            "torch_strength" -> {
                val strength = value.toIntOrNull() ?: return
                prefs.edit().putString(getLensPrefKey("camera_torch_strength"), strength.toString()).apply()
                launchMain { setTorchStrength(strength) }
            }
            "zoom" -> {
                val zoomFactor = value.toFloatOrNull() ?: return
                prefs.edit().putString(getLensPrefKey("camera_zoom"), zoomFactor.toString()).apply()
                launchMain { cam?.cameraControl?.setZoomRatio(zoomFactor) }
            }
            "fps" -> {
                val fps = value.toIntOrNull() ?: return
                prefs.edit().putInt(getLensPrefKey("camera_fps"), fps).apply()
                Log.i(TAG, "Remote Control: Target FPS set to $fps")
                launchMain { applyCamera2InteropSettings() }
            }
            "software_zoom" -> {
                val softwareZoom = value.toFloatOrNull() ?: return
                prefs.edit().putString(getLensPrefKey("software_zoom"), softwareZoom.toString()).apply()
            }
            "exposure" -> {
                val exposure = value.toIntOrNull() ?: return
                prefs.edit().putString(getLensPrefKey("camera_exposure"), exposure.toString()).apply()
                Log.i(TAG, "Remote Control: Exposure set to $exposure (hardware index + software boost)")
                launchMain { cam?.cameraControl?.setExposureCompensationIndex(exposure) }
            }
            "contrast" -> {
                val contrast = value.toIntOrNull() ?: return
                prefs.edit().putString(getLensPrefKey("camera_contrast"), contrast.toString()).apply()
                Log.i(TAG, "Remote Control: Contrast set to $contrast (software-based)")
            }
            "resolution" -> {
                if (value in listOf("low", "medium", "high", "4k", "1080p", "720p", "480p", "240p")) {
                    prefs.edit().putString(getLensPrefKey("camera_resolution"), value).apply()
                    launchMain { startCamera() } // Restart
                }
            }
            "camera" -> {
                lensFacing = if (value == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                prefs.edit().putString(PREF_LAST_CAMERA_FACING, value).apply()
                cameraResolutionHelper = null
                launchMain { startCamera() }
            }
            "lens_id" -> {
                prefs.edit().putString("camera_lens_id", value).apply()
                cameraResolutionHelper = null
                launchMain { startCamera() }
            }
            // Other settings like scale/delay/rotate are handled in processImage
            "scale" -> {
                val scale = value.toFloatOrNull() ?: return
                if (scale in 0.5f..2.0f) prefs.edit().putString(getLensPrefKey("stream_scale"), value).apply()
            }
            "delay" -> {
                val delay = value.toLongOrNull() ?: return
                if (delay in 10L..1000L) prefs.edit().putString(getLensPrefKey("stream_delay"), value).apply()
            }
            "rotate" -> {
                val isFront = lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA
                val prefKey = if (isFront) "camera_manual_rotate_front" else "camera_manual_rotate_back"
                val currentRotation = prefs.getInt(prefKey, 0)
                val nextRotation = (currentRotation + 90) % 360
                prefs.edit().putInt(prefKey, nextRotation).apply()
            }
            "zoom_focus" -> {
                val parts = value.split(",")
                if (parts.size == 2) {
                    val x = parts[0].toFloatOrNull() ?: 0.5f
                    val y = parts[1].toFloatOrNull() ?: 0.5f
                    prefs.edit()
                        .putFloat(getLensPrefKey("camera_zoom_focus_x"), x)
                        .putFloat(getLensPrefKey("camera_zoom_focus_y"), y)
                        .apply()
                    // Trigger auto-focus at the new zoom focus point
                    launchMain { triggerAutoFocus(x, y) }
                }
            }
            "focus_mode" -> {
                if (value in listOf("auto", "manual")) {
                    prefs.edit().putString(getLensPrefKey("camera_focus_mode"), value).apply()
                    launchMain { applyCamera2InteropSettings() }
                }
            }
            "focus_distance" -> {
                val distance = value.toFloatOrNull() ?: return
                prefs.edit().putFloat(getLensPrefKey("camera_focus_distance"), distance).apply()
                launchMain { applyCamera2InteropSettings() }
            }
            "exposure_mode" -> {
                if (value in listOf("auto", "manual")) {
                    prefs.edit().putString(getLensPrefKey("camera_exposure_mode"), value).apply()
                    launchMain { applyCamera2InteropSettings() }
                }
            }
            "iso" -> {
                val iso = value.toIntOrNull() ?: return
                prefs.edit().putInt(getLensPrefKey("camera_iso"), iso).apply()
                launchMain { applyCamera2InteropSettings() }
            }
            "shutter" -> {
                val shutter = value.toLongOrNull() ?: return
                prefs.edit().putLong(getLensPrefKey("camera_shutter"), shutter).apply()
                launchMain { applyCamera2InteropSettings() }
            }
            "pixel_binning" -> {
                val enabled = value == "1" || value == "on"
                prefs.edit().putBoolean(getLensPrefKey("camera_pixel_binning"), enabled).apply()
                Log.i(TAG, "Pixel Binning: ${if (enabled) "Enabled" else "Disabled"}")
            }
            "autofocus" -> {
                val parts = value.split(",")
                if (parts.size == 2) {
                    val x = parts[0].toFloatOrNull() ?: 0.5f
                    val y = parts[1].toFloatOrNull() ?: 0.5f
                    launchMain { triggerAutoFocus(x, y) }
                } else {
                    launchMain { triggerAutoFocus() }
                }
            }
            "power_saving" -> {
                val enabled = value == "1" || value == "on" || value == "true"
                prefs.edit().putBoolean("power_saving_mode", enabled).apply()
                launchMain { 
                    startCamera() // Restart to apply changes
                }
            }
            "screen_black" -> {
                val next = when (value.lowercase()) {
                    "on", "1", "true" -> true
                    "off", "0", "false" -> false
                    "toggle" -> !prefs.getBoolean("screen_black_mode", false)
                    else -> return
                }
                prefs.edit().putBoolean("screen_black_mode", next).apply()
                launchMain { onScreenBlackChanged?.invoke(next) }
            }
        }
    }

    private val clientBusyMap = ConcurrentHashMap<StreamingServerHelper.Client, Boolean>()

    private fun processImage(image: ImageProxy) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val isPowerSaving = prefs.getBoolean("power_saving_mode", false)
            val delay = if (isPowerSaving) 1000L else (prefs.getString(getLensPrefKey("stream_delay"), "33")?.toLongOrNull() ?: 33L)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFrameTime < delay) {
                return
            }
            lastFrameTime = currentTime

            val autoRotation = image.imageInfo.rotationDegrees
            val isFront = lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA
            val prefKey = if (isFront) "camera_manual_rotate_front" else "camera_manual_rotate_back"
            val manualRotation = prefs.getInt(prefKey, 0)
            val totalRotation = (autoRotation + manualRotation) % 360
            val quality = if (isPowerSaving) "240p" else (prefs.getString(getLensPrefKey("camera_resolution"), "low") ?: "low")
            
            // Hard target widths for software downscaling to ensure user actually gets lower quality/bandwidth
            val targetWidth = when (quality) {
                "4k" -> 3840
                "1080p", "high" -> 1920
                "720p", "medium" -> 1280
                "480p", "low" -> 640
                "240p" -> 320
                else -> 640
            }

            val softwareZoom = prefs.getString(getLensPrefKey("software_zoom"), "1.0")?.toFloatOrNull() ?: 1.0f
            // Use lens-specific cached values or preference
            val zoomFocusX = prefs.getFloat(getLensPrefKey("camera_zoom_focus_x"), 0.5f)
            val zoomFocusY = prefs.getFloat(getLensPrefKey("camera_zoom_focus_y"), 0.5f)
            val contrastValue = prefs.getString(getLensPrefKey("camera_contrast"), "0")?.toIntOrNull() ?: 0
            val exposureValue = prefs.getString(getLensPrefKey("camera_exposure"), "0")?.toIntOrNull() ?: 0
            
            // Convert YUV_420_888 to NV21
            var nv21 = convertYUV420toNV21(image)
            val crop = image.cropRect
            
            // Initial dimensions from crop
            var width = crop.width() and 1.inv()
            var height = crop.height() and 1.inv()
            
            // Software Pixel Binning (2x2) - Process at raw NV21 level for maximum SNR gain
            val isBinningEnabled = prefs.getBoolean(getLensPrefKey("camera_pixel_binning"), false)
            if (isBinningEnabled && width >= 640 && height >= 480 && width % 4 == 0 && height % 4 == 0) {
                try {
                    val binned = performSoftwareBinning(nv21, width, height)
                    nv21 = binned.first
                    width = binned.second
                    height = binned.third
                } catch (e: Exception) {
                    Log.e(TAG, "Pixel Binning failed: ${e.message}")
                }
            }
            
            // Log resolution occasionally
            if (SystemClock.elapsedRealtime() % 10000 < 100) {
                Log.d(TAG, "Processing frame: ${width}x${height}, Binning: $isBinningEnabled")
            }

            // Convert NV21 to JPEG for snapshot and as a base for transformation
            var jpegBytes = convertNV21toJPEG(nv21, width, height)
            if (jpegBytes.isEmpty()) {
                return
            }
            lastSnapshot = jpegBytes

            // Apply transformations if needed (Rotation, Scaling, Zoom, Contrast, Exposure)
            val uiScale = prefs.getString(getLensPrefKey("stream_scale"), "1.0")?.toFloatOrNull() ?: 1.0f
            val needsTransform = totalRotation != 0 || uiScale != 1.0f || softwareZoom > 1.0f || contrastValue != 0 || exposureValue != 0 || width > targetWidth

            if (needsTransform) {
                try {
                    val options = BitmapFactory.Options().apply {
                        if (width > targetWidth * 2) {
                            inSampleSize = 2
                            if (width > targetWidth * 4) inSampleSize = 4
                        }
                    }

                    var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options) ?: return

                    val matrix = Matrix()
                    if (totalRotation != 0) {
                        matrix.postRotate(totalRotation.toFloat())
                    }
                    if (uiScale != 1.0f) {
                        matrix.postScale(uiScale, uiScale)
                    }

                    var workingBitmap = if (!matrix.isIdentity) {
                        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        if (transformed !== bitmap) bitmap.recycle()
                        transformed
                    } else {
                        bitmap
                    }

                    if (softwareZoom > 1.0f) {
                        val cropW = (workingBitmap.width / softwareZoom).toInt().coerceIn(1, workingBitmap.width)
                        val cropH = (workingBitmap.height / softwareZoom).toInt().coerceIn(1, workingBitmap.height)
                        val centerX = (zoomFocusX * workingBitmap.width).toInt()
                        val centerY = (zoomFocusY * workingBitmap.height).toInt()
                        val left = (centerX - cropW / 2).coerceIn(0, workingBitmap.width - cropW)
                        val top = (centerY - cropH / 2).coerceIn(0, workingBitmap.height - cropH)
                        val cropped = Bitmap.createBitmap(workingBitmap, left, top, cropW, cropH)
                        if (cropped !== workingBitmap) workingBitmap.recycle()
                        workingBitmap = cropped
                    }

                    val desiredWidth = (targetWidth.toFloat() * uiScale).toInt().coerceAtLeast(1)
                    if (workingBitmap.width != desiredWidth) {
                        val newW = desiredWidth.coerceAtLeast(1)
                        val newH = ((workingBitmap.height.toFloat() / workingBitmap.width.toFloat()) * newW).toInt().coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(workingBitmap, newW, newH, true)
                        if (scaled !== workingBitmap) workingBitmap.recycle()
                        workingBitmap = scaled
                    }

                    if (contrastValue != 0 || exposureValue != 0) {
                        val contrastFactor = 1.0f + (contrastValue / 100.0f)
                        
                        // Software Exposure Gain (Digital Gain)
                        // Map -10..10 to a gain factor of 0.5x..2.0x
                        val exposureFactor = if (exposureValue >= 0) {
                            1.0f + (exposureValue * 0.1f) // 1.0x to 2.0x
                        } else {
                            1.0f + (exposureValue * 0.05f) // 0.5x to 1.0x
                        }
                        
                        val paint = android.graphics.Paint().apply {
                            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
                                // Combined Contrast and Brightness/Exposure Matrix
                                // out = (in - 128) * contrastFactor + 128 + brightnessOffset
                                // But for exposure we use a multiplier (Digital Gain)
                                // Final: out = ((in * contrastFactor) + 128 * (1 - contrastFactor)) * exposureFactor
                                val c = contrastFactor
                                val e = exposureFactor
                                val offset = 128f * (1f - c) * e
                                
                                set(floatArrayOf(
                                    c * e, 0f,    0f,    0f, offset,
                                    0f,    c * e, 0f,    0f, offset,
                                    0f,    0f,    c * e, 0f, offset,
                                    0f,    0f,    0f,    1f, 0f
                                ))
                            })
                        }
                        val transformed = Bitmap.createBitmap(
                            workingBitmap.width,
                            workingBitmap.height,
                            workingBitmap.config ?: Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(transformed)
                        canvas.drawBitmap(workingBitmap, 0f, 0f, paint)
                        workingBitmap.recycle()
                        workingBitmap = transformed
                    }

                    val outputStream = java.io.ByteArrayOutputStream()
                    workingBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                    jpegBytes = outputStream.toByteArray()
                    workingBitmap.recycle()
                    outputStream.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error transforming image: ${e.message}")
                }
            }

            streamingServerHelper?.getClients()?.let { clients ->
                clients.forEach { client ->
                    // Skip if client is still busy sending previous frame
                    if (clientBusyMap[client] == true) return@forEach
                    
                    clientBusyMap[client] = true
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            synchronized(client.writer) {
                                client.writer.print("--frame\r\n")
                                client.writer.print("Content-Type: image/jpeg\r\n")
                                client.writer.print("Content-Length: ${jpegBytes.size}\r\n\r\n")
                                client.writer.flush()
                                client.outputStream.write(jpegBytes)
                                client.outputStream.flush()
                                streamingServerHelper?.addBytesSent(jpegBytes.size)
                            }
                        } catch (e: IOException) {
                            try { client.socket.close() } catch (_: Exception) {}
                            streamingServerHelper?.removeClient(client)
                            clientBusyMap.remove(client)
                        } finally {
                            clientBusyMap[client] = false
                        }
                    }
                }
                
                // Cleanup busy map for disconnected clients
                val currentClients = clients.toSet()
                clientBusyMap.keys.removeIf { it !in currentClients }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in processImage: ${e.message}")
        }
    }

    private fun generateRandomPassword(): String {
        val random = SecureRandom()
        val uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val lowercase = "abcdefghijklmnopqrstuvwxyz"
        val digits = "0123456789"
        val allChars = uppercase + lowercase + digits
        val password = StringBuilder().apply {
            append(uppercase[random.nextInt(uppercase.length)])
            append(lowercase[random.nextInt(lowercase.length)])
            append(digits[random.nextInt(digits.length)])
            repeat(9) { append(allChars[random.nextInt(allChars.length)]) }
        }
        val passwordArray = password.toString().toCharArray()
        for (i in passwordArray.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val temp = passwordArray[i]
            passwordArray[i] = passwordArray[j]
            passwordArray[j] = temp
        }
        return String(passwordArray)
    }

    private fun allPermissionsGranted() = arrayOf(Manifest.permission.CAMERA).all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
}
