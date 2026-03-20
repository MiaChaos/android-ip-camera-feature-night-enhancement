package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size

class CameraResolutionHelper(private val context: Context) {
    var highResolution: Size? = null
    var mediumResolution: Size? = null
    var lowResolution: Size? = null
    var uhdResolution: Size? = null // 4K
    var fhdResolution: Size? = null // 1080p
    var hdResolution: Size? = null  // 720p
    var sdResolution: Size? = null  // 480p
    var lqResolution: Size? = null  // 360p

    fun initializeResolutions(cameraId: String) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val supportedSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
                if (supportedSizes != null && supportedSizes.isNotEmpty()) {
                    val sortedSizes = supportedSizes.sortedByDescending { it.width * it.height }
                    
                    // Specific resolutions for S10 and modern phones
                    uhdResolution = sortedSizes.find { it.width == 3840 && it.height == 2160 }
                                    ?: sortedSizes.find { it.width in 3500..4000 && it.height in 2000..2500 }

                    fhdResolution = sortedSizes.find { it.width == 1920 && it.height == 1080 } 
                                    ?: sortedSizes.find { it.width in 1800..2000 && it.height in 1000..1200 }
                    
                    hdResolution = sortedSizes.find { it.width == 1280 && it.height == 720 }
                                   ?: sortedSizes.find { it.width in 1200..1400 && it.height in 700..800 }
                    
                    sdResolution = sortedSizes.find { it.width == 640 && it.height == 480 }
                                   ?: sortedSizes.find { it.width in 600..800 && it.height in 400..600 }
                    
                    lqResolution = sortedSizes.find { it.width == 320 && it.height == 240 }
                                   ?: sortedSizes.last()

                    Log.i(TAG, "Initialized resolutions for $cameraId:")
                    Log.i(TAG, "  UHD (4K): ${uhdResolution?.width}x${uhdResolution?.height}")
                    Log.i(TAG, "  FHD (1080p): ${fhdResolution?.width}x${fhdResolution?.height}")
                    Log.i(TAG, "  HD (720p): ${hdResolution?.width}x${hdResolution?.height}")
                    Log.i(TAG, "  SD (480p): ${sdResolution?.width}x${sdResolution?.height}")
                    Log.i(TAG, "  LQ (240p): ${lqResolution?.width}x${lqResolution?.height}")

                    // Maintain legacy mapping for compatibility
                    highResolution = uhdResolution ?: fhdResolution ?: hdResolution ?: sortedSizes.first()
                    mediumResolution = fhdResolution ?: hdResolution ?: sdResolution ?: sortedSizes[sortedSizes.size / 2]
                    lowResolution = sdResolution ?: lqResolution ?: sortedSizes.last()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing resolutions: ${e.message}")
        }
    }

    fun getResolutionForQuality(quality: String): Size? {
        return when (quality) {
            "4k" -> uhdResolution
            "1080p" -> fhdResolution
            "720p" -> hdResolution
            "480p" -> sdResolution
            "240p" -> lqResolution
            "high" -> highResolution
            "medium" -> mediumResolution
            "low" -> lowResolution
            else -> lowResolution
        }
    }

    companion object {
        private const val TAG = "CameraResolutionHelper"
    }
}
