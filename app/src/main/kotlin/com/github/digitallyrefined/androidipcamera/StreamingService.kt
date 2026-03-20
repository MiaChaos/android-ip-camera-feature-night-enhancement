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

    fun triggerAutoFocus() {
        val cam = camera ?: return
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                val point = factory.createPoint(0.5f, 0.5f)
                
                // 1. Temporarily clear manual overrides to allow AF/AE to work
                val camera2Control = Camera2CameraControl.from(cam.cameraControl)
                camera2Control.captureRequestOptions = CaptureRequestOptions.Builder().build()
                
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                Log.i(TAG, "Force Auto Focus: Triggering AF/AE...")
                val result = cam.cameraControl.startFocusAndMetering(action)
                
                // 2. Once the action is triggered (or after a short delay), restore manual settings
                // We use a listener to ensure we don't fight with the metering action immediately
                result.addListener({
                    Log.i(TAG, "Force Auto Focus: Action completed, restoring manual settings")
                    applyCamera2InteropSettings()
                }, ContextCompat.getMainExecutor(this@StreamingService))
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger auto focus: ${e.message}")
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
            if (finalMinFocus == 0f && lensFacing == CameraSelector.DEFAULT_BACK_CAMERA) {
                finalMinFocus = 10f // Default fallback for back camera
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
        
        return mapOf(
            "battery" to batteryPct,
            "isCharging" to isCharging,
            "temp" to temp,
            "uptime" to (SystemClock.elapsedRealtime() / 1000), // seconds
            "minFocusDistance" to minFocusDistance,
            "isoMin" to isoMin,
            "isoMax" to isoMax,
            "shutterMin" to shutterMin,
            "shutterMax" to shutterMax
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
                    val quality = prefs.getString("camera_resolution", "low") ?: "low"
                    val targetResolution = cameraResolutionHelper?.getResolutionForQuality(quality)

                    if (targetResolution != null) {
                        Log.i(TAG, "Requesting resolution: $quality -> ${targetResolution.width}x${targetResolution.height}")
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
        return when (lensFacing) {
            CameraSelector.DEFAULT_BACK_CAMERA -> {
                cameraManager.cameraIdList.find { id ->
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: "0"
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
        val requestedZoomFactor = prefs.getString("camera_zoom", "1.0")?.toFloatOrNull() ?: 1.0f
        cam.cameraControl.setZoomRatio(requestedZoomFactor)

        // Exposure Compensation (Basic CameraX API)
        val exposureValue = prefs.getString("camera_exposure", "0")?.toIntOrNull() ?: 0
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
            val focusMode = prefs.getString("camera_focus_mode", "auto") ?: "auto"
            if (focusMode == "manual") {
                val distance = prefs.getFloat("camera_focus_distance", 0f)
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
            }

            // 2. Manual Exposure (ISO/Shutter)
            val exposureMode = prefs.getString("camera_exposure_mode", "auto") ?: "auto"
            if (exposureMode == "manual") {
                val iso = prefs.getInt("camera_iso", 400)
                val shutterNs = prefs.getLong("camera_shutter", 20000000L)
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
            }

            // 3. Torch Strength (API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val flashStrengthLevelField = CaptureRequest::class.java.getDeclaredField("FLASH_STRENGTH_LEVEL")
                    @Suppress("UNCHECKED_CAST")
                    val flashStrengthLevelKey = flashStrengthLevelField.get(null) as CaptureRequest.Key<Int>
                    
                    val strength = prefs.getString("camera_torch_strength", "1")?.toIntOrNull() ?: 1
                    builder.setCaptureRequestOption(flashStrengthLevelKey, strength)
                    
                    val torchPref = prefs.getString("camera_torch", "off")
                    if (torchPref == "on") {
                        builder.setCaptureRequestOption(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    }
                } catch (_: Exception) {}
            }

            camera2Control.captureRequestOptions = builder.build()
            Log.d(TAG, "Applied unified Camera2 settings: Focus=$focusMode, Exposure=$exposureMode")
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
                prefs.edit().putString("camera_torch_strength", strength.toString()).apply()
                launchMain { setTorchStrength(strength) }
            }
            "zoom" -> {
                val zoomFactor = value.toFloatOrNull() ?: return
                prefs.edit().putString("camera_zoom", zoomFactor.toString()).apply()
                launchMain { cam?.cameraControl?.setZoomRatio(zoomFactor) }
            }
            "exposure" -> {
                val exposure = value.toIntOrNull() ?: return
                prefs.edit().putString("camera_exposure", exposure.toString()).apply()
                launchMain { cam?.cameraControl?.setExposureCompensationIndex(exposure) }
            }
            "contrast" -> {
                val contrast = value.toIntOrNull() ?: return
                prefs.edit().putString("camera_contrast", contrast.toString()).apply()
                Log.i(TAG, "Remote Control: Contrast set to $contrast (software-based)")
            }
            "resolution" -> {
                if (value in listOf("low", "medium", "high", "4k", "1080p", "720p", "480p", "240p")) {
                    prefs.edit().putString("camera_resolution", value).apply()
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
                if (scale in 0.5f..2.0f) prefs.edit().putString("stream_scale", value).apply()
            }
            "delay" -> {
                val delay = value.toLongOrNull() ?: return
                if (delay in 10L..1000L) prefs.edit().putString("stream_delay", value).apply()
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
                        .putFloat("camera_zoom_focus_x", x)
                        .putFloat("camera_zoom_focus_y", y)
                        .apply()
                }
            }
            "focus_mode" -> {
                if (value in listOf("auto", "manual")) {
                    prefs.edit().putString("camera_focus_mode", value).apply()
                    launchMain { applyCamera2InteropSettings() }
                }
            }
            "focus_distance" -> {
                val distance = value.toFloatOrNull() ?: return
                prefs.edit().putFloat("camera_focus_distance", distance).apply()
                launchMain { applyCamera2InteropSettings() }
            }
            "exposure_mode" -> {
                if (value in listOf("auto", "manual")) {
                    prefs.edit().putString("camera_exposure_mode", value).apply()
                    launchMain { applyCamera2InteropSettings() }
                }
            }
            "iso" -> {
                val iso = value.toIntOrNull() ?: return
                prefs.edit().putInt("camera_iso", iso).apply()
                launchMain { applyCamera2InteropSettings() }
            }
            "shutter" -> {
                val shutter = value.toLongOrNull() ?: return
                prefs.edit().putLong("camera_shutter", shutter).apply()
                launchMain { applyCamera2InteropSettings() }
            }
            "autofocus" -> {
                launchMain { triggerAutoFocus() }
            }
        }
    }

    private val clientBusyMap = ConcurrentHashMap<StreamingServerHelper.Client, Boolean>()

    private fun processImage(image: ImageProxy) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val delay = prefs.getString("stream_delay", "33")?.toLongOrNull() ?: 33L
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
            val quality = prefs.getString("camera_resolution", "low") ?: "low"
            
            // Hard target widths for software downscaling to ensure user actually gets lower quality/bandwidth
            val targetWidth = when (quality) {
                "4k" -> 3840
                "1080p", "high" -> 1920
                "720p", "medium" -> 1280
                "480p", "low" -> 640
                "240p" -> 320
                else -> 640
            }

            val scaleFactor = prefs.getString("stream_scale", "1.0")?.toFloatOrNull() ?: 1.0f
            val zoomFactor = prefs.getString("camera_zoom", "1.0")?.toFloatOrNull() ?: 1.0f
            // Use cached values for performance (reduces frame jitter)
            val zoomFocusX = this.zoomFocusX
            val zoomFocusY = this.zoomFocusY
            val contrastValue = prefs.getString("camera_contrast", "0")?.toIntOrNull() ?: 0

            // Convert YUV_420_888 to NV21
            val nv21 = convertYUV420toNV21(image)
            val crop = image.cropRect
            
            // Ensure dimensions are even to avoid issues with some encoders/decoders
            val width = crop.width() and 1.inv()
            val height = crop.height() and 1.inv()
            
            // Log resolution occasionally
            if (SystemClock.elapsedRealtime() % 10000 < 100) {
                Log.d(TAG, "Source frame resolution: ${width}x${height}")
            }

            // Convert NV21 to JPEG - USE CROP DIMENSIONS
            var jpegBytes = convertNV21toJPEG(nv21, width, height)
            
            // Store as latest snapshot
            lastSnapshot = jpegBytes

            // Apply transformations if needed (Rotation, Scaling, Zoom, Contrast)
            if (totalRotation != 0 || scaleFactor != 1.0f || zoomFactor != 1.0f || contrastValue != 0 || width > targetWidth) {
                try {
                    var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    if (bitmap != null) {
                        val srcWidth = bitmap.width
                        val srcHeight = bitmap.height
                        
                        val finalMatrix = Matrix()
                        if (totalRotation != 0) {
                            finalMatrix.postRotate(totalRotation.toFloat())
                        }
                        
                        var workingBitmap = bitmap
                        if (totalRotation != 0) {
                            workingBitmap = Bitmap.createBitmap(bitmap, 0, 0, srcWidth, srcHeight, finalMatrix, true)
                            if (workingBitmap != bitmap) bitmap.recycle()
                        }
                        
                        if (zoomFactor > 1.0f) {
                            val w = workingBitmap.width
                            val h = workingBitmap.height
                            
                            val cropW = w / zoomFactor
                            val cropH = h / zoomFactor
                            
                            var startX = (zoomFocusX * w) - (cropW / 2f)
                            var startY = (zoomFocusY * h) - (cropH / 2f)
                            
                            if (startX < 0) startX = 0f
                            if (startY < 0) startY = 0f
                            if (startX + cropW > w) startX = w - cropW
                            if (startY + cropH > h) startY = h - cropH
                            
                            val zoomedBitmap = Bitmap.createBitmap(
                                workingBitmap, startX.toInt(), startY.toInt(), cropW.toInt(), cropH.toInt()
                            )
                            if (zoomedBitmap != workingBitmap) workingBitmap.recycle()
                            workingBitmap = zoomedBitmap
                        }
                        
                        // Combined scaling: resolution target * user scale factor
                        val autoScale = if (workingBitmap.width > targetWidth) targetWidth.toFloat() / workingBitmap.width else 1.0f
                        val finalScale = autoScale * scaleFactor
                        
                        if (finalScale != 1.0f) {
                            val scaledW = (workingBitmap.width * finalScale).toInt()
                            val scaledH = (workingBitmap.height * finalScale).toInt()
                            if (scaledW > 0 && scaledH > 0) {
                                val scaledBitmap = Bitmap.createScaledBitmap(workingBitmap, scaledW, scaledH, true)
                                if (scaledBitmap != workingBitmap) workingBitmap.recycle()
                                workingBitmap = scaledBitmap
                            }
                        }
                        
                        bitmap = workingBitmap
                        
                        if (contrastValue != 0) {
                            val contrastFactor = 1.0f + (contrastValue / 100.0f)
                            val contrastColorMatrix = android.graphics.ColorMatrix().apply {
                                set(floatArrayOf(
                                contrastFactor, 0f, 0f, 0f, 0f,
                                0f, contrastFactor, 0f, 0f, 0f,
                                0f, 0f, contrastFactor, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                                ))
                            }
                            val paint = android.graphics.Paint().apply {
                                colorFilter = android.graphics.ColorMatrixColorFilter(contrastColorMatrix)
                            }
                            val contrastedBitmap = Bitmap.createBitmap(
                                bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888
                            )
                            val canvas = android.graphics.Canvas(contrastedBitmap)
                            canvas.drawBitmap(bitmap, 0f, 0f, paint)
                            bitmap.recycle()
                            bitmap = contrastedBitmap
                        }

                        val outputStream = java.io.ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                        jpegBytes = outputStream.toByteArray()
                        
                        // Store the processed JPEG as latest snapshot
                        lastSnapshot = jpegBytes
                        Log.d(TAG, "Snapshot updated (processed): ${jpegBytes.size} bytes")
                        
                        bitmap.recycle()
                        outputStream.close()
                    }
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
