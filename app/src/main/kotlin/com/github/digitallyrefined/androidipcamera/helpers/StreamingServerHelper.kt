package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.StreamingService
import com.github.digitallyrefined.androidipcamera.helpers.SecureStorage
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

class StreamingServerHelper(
    private val context: Context,
    private val streamPort: Int = 4444,
    private val maxClients: Int = 3,
    private val maxAuthenticatedClients: Int = 10, // Higher limit for authenticated users
    private val onLog: (String) -> Unit = {},
    private val onClientConnected: () -> Unit = {},
    private val onClientDisconnected: () -> Unit = {},
    private val onControlCommand: (String, String) -> Unit = { _, _ -> }
) {
    data class Client(
        val socket: Socket,
        val outputStream: OutputStream,
        val writer: PrintWriter,
        val connectedAt: Long = System.currentTimeMillis(),
        val isAuthenticated: Boolean = false
    )

    private data class FailedAttempt(
        var count: Int = 0,
        var lastAttempt: Long = 0L,
        var blockedUntil: Long = 0L
    )

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    @Volatile
    private var isStarting = false
    private val clients = CopyOnWriteArrayList<Client>()
    private val failedAttempts = ConcurrentHashMap<String, FailedAttempt>()
    @Volatile
    private var appInForeground: Boolean = true

    // SECURITY: Rate limiting constants (only for unauthenticated connections)
    private val MAX_FAILED_ATTEMPTS = 5  // 5 failed attempts allowed
    private val BLOCK_DURATION_MS = 15 * 60 * 1000L // 15 minutes block for unauthenticated
    private val RESET_WINDOW_MS = 10 * 60 * 1000L // 10 minutes reset window

    // Connection limits
    private val MAX_CONNECTION_DURATION_MS = 30 * 60 * 1000L // 30 minutes max per connection (unauthenticated)
    private val MAX_AUTHENTICATED_CONNECTION_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours for authenticated users
    private val CONNECTION_READ_TIMEOUT_MS = 30 * 1000 // 30 seconds read timeout
    private val SOCKET_TIMEOUT_MS = 60 * 1000 // 60 seconds socket timeout

    fun getClients(): List<Client> = clients.toList()

    private fun isRateLimited(clientIp: String): Boolean {
        val now = System.currentTimeMillis()
        val attempt = failedAttempts.getOrPut(clientIp) { FailedAttempt() }

        // Check if currently blocked
        if (now < attempt.blockedUntil) {
            return true
        }

        // Reset counter if outside window
        if (now - attempt.lastAttempt > RESET_WINDOW_MS) {
            attempt.count = 0
        }

        return false
    }

    private fun recordFailedAttempt(clientIp: String) {
        val now = System.currentTimeMillis()
        val attempt = failedAttempts.getOrPut(clientIp) { FailedAttempt() }

        // Prevent integer overflow
        if (attempt.count < Int.MAX_VALUE - 1) {
            attempt.count++
        }
        attempt.lastAttempt = now

        if (attempt.count >= MAX_FAILED_ATTEMPTS) {
            attempt.blockedUntil = now + BLOCK_DURATION_MS
            onLog("SECURITY: IP $clientIp blocked for ${BLOCK_DURATION_MS / (60 * 1000)} minutes due to too many unauthenticated attempts")
        }
    }

    fun startStreamingServer() {
        // Prevent concurrent starts
        synchronized(this) {
            if (isStarting) {
                return
            }

            // Check if server is already running
            if (serverJob != null && serverSocket != null && !serverSocket!!.isClosed) {
                return
            }

            isStarting = true
        }

        // Show toast when starting server
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Server starting...", Toast.LENGTH_SHORT).show()
        }

        // Stop existing server BEFORE creating new one (outside the coroutine)
        // This must be done to avoid cancelling the new job
        val oldJob: Job?
        val oldSocket: ServerSocket?
        synchronized(this) {
            oldJob = serverJob
            oldSocket = serverSocket
            serverJob = null
            serverSocket = null
        }

        // Stop old server and wait for it to fully stop (if it exists)
        if (oldJob != null || oldSocket != null) {
            runBlocking(Dispatchers.IO) {
                try {
                    oldSocket?.close()
                } catch (e: IOException) {
                    onLog("Error closing old server socket: ${e.message}")
                }
                oldJob?.cancel()
                try {
                    oldJob?.join()
                } catch (e: Exception) {
                    // Ignore cancellation exceptions
                }
                // Small delay to ensure port is released
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        synchronized(this) {
              serverJob = CoroutineScope(Dispatchers.IO).launch {
              try {
                  val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                  val secureStorage = SecureStorage(context)
                  val certificatePath = prefs.getString("certificate_path", null)

                  val rawPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, null)
                  val certificatePassword = rawPassword?.let {
                      if (it.isEmpty()) null else it.toCharArray()
                  }

                  // Certificate setup required - no defaults
                  var finalCertificatePath = certificatePath
                  var finalCertificatePassword = certificatePassword

                  if (certificatePath == null) {
                      // Use personal certificate from assets - requires password configuration
                      try {
                          val personalCertFile = File(context.filesDir, "personal_certificate.p12")
                          if (!personalCertFile.exists()) {
                              // Try to copy from assets first
                              try {
                                  context.assets.open("personal_certificate.p12").use { input ->
                                      personalCertFile.outputStream().use { output ->
                                          input.copyTo(output)
                                      }
                                  }
                              } catch (assetException: Exception) {
                                  // Certificate not in assets - provide helpful error
                                  Handler(Looper.getMainLooper()).post {
                                      onLog("Certificate not found.")
                                      Toast.makeText(context,
                                          "Certificate missing, reset app to generate a new certificate",
                                          Toast.LENGTH_LONG).show()
                                  }
                                  return@launch
                              }
                          }
                          finalCertificatePath = personalCertFile.absolutePath

                          // Require certificate password to be configured
                          if (finalCertificatePassword == null) {
                              return@launch
                          }

                      } catch (e: Exception) {
                          Handler(Looper.getMainLooper()).post {
                              onLog("ERROR: Could not load certificate: ${e.message}")
                              Toast.makeText(context, "Certificate error, check certificate file and password in Settings", Toast.LENGTH_LONG).show()
                          }
                          return@launch
                      }
                  }

                  val bindAddress = InetAddress.getByName("0.0.0.0")

                  serverSocket = try {
                      // Determine which certificate file to use
                      val certFile = if (certificatePath != null) {
                          // Custom certificate - copy from URI to local file
                          val uri = certificatePath.toUri()
                          val privateFile = File(context.filesDir, "certificate.p12")
                          if (privateFile.exists()) privateFile.delete()
                          context.contentResolver.openInputStream(uri)?.use { input ->
                              privateFile.outputStream().use { output ->
                                  input.copyTo(output)
                              }
                          } ?: throw IOException("Failed to open certificate file")
                          privateFile
                      } else {
                          // Personal certificate
                          File(finalCertificatePath!!)
                      }

                      certFile.inputStream().use { inputStream ->
                          try {
                              val keyStore = KeyStore.getInstance("PKCS12")
                              keyStore.load(inputStream, finalCertificatePassword)
                              val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                              keyManagerFactory.init(keyStore, finalCertificatePassword)
                              val sslContext = SSLContext.getInstance("TLSv1.3")
                              sslContext.init(keyManagerFactory.keyManagers, null, null)
                              val sslServerSocketFactory = sslContext.serverSocketFactory
                              (sslServerSocketFactory.createServerSocket(streamPort, 50, bindAddress) as SSLServerSocket).apply {
                                  reuseAddress = true
                                  enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                                  enabledCipherSuites = arrayOf(
                                      "TLS_AES_256_GCM_SHA384",
                                      "TLS_AES_128_GCM_SHA256",
                                      "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                                      "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
                                  )
                                  soTimeout = 30000
                              }
                          } catch (keystoreException: Exception) {
                              Handler(Looper.getMainLooper()).post {
                                  onLog("Certificate loading failed: ${keystoreException.message}")
                                  val errorMsg = when {
                                      keystoreException.message?.contains("password") == true ->
                                          "Certificate password is incorrect, check Settings > Advanced Security"
                                      keystoreException.message?.contains("keystore") == true ->
                                          "Certificate file is corrupted or invalid, regenerate with setup.bat"
                                      else ->
                                          "Certificate error: ${keystoreException.message}"
                                  }
                                  Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                              }
                              return@launch
                          }
                      }
                  } catch (e: Exception) {
                      Handler(Looper.getMainLooper()).post {
                          onLog("CRITICAL: Failed to create HTTPS server: ${e.message}")
                          Toast.makeText(context, "Failed to start secure HTTPS server: ${e.message}", Toast.LENGTH_LONG).show()
                      }
                      return@launch
                  }
                  onLog("Server started on port $streamPort (HTTPS)")
                  // Clear the starting flag now that server is running
                  synchronized(this@StreamingServerHelper) {
                      isStarting = false
                  }
                  Handler(Looper.getMainLooper()).post {
                      Toast.makeText(context, "Server started", Toast.LENGTH_SHORT).show()
                  }
                  while (isActive && !Thread.currentThread().isInterrupted) {
                      try {
                          val socket = serverSocket?.accept() ?: continue
                          val clientIp = socket.inetAddress.hostAddress

                          // Handle each connection in a separate coroutine to avoid blocking the accept loop
                          CoroutineScope(Dispatchers.IO).launch {
                              handleClientConnection(socket, clientIp)
                          }
                      } catch (e: IOException) {
                          // Check if server socket was closed
                          if (serverSocket == null || serverSocket!!.isClosed) {
                              onLog("Server socket closed, stopping server")
                              break
                          }
                          // Ignore other connection errors
                      } catch (e: InterruptedException) {
                          Thread.currentThread().interrupt()
                          break
                      } catch (e: Exception) {
                          // Check if server socket was closed
                          if (serverSocket == null || serverSocket!!.isClosed) {
                              onLog("Server socket closed, stopping server")
                              break
                          }
                          onLog("Unexpected error in server loop: ${e.message}")
                      }
                  }
              } catch (e: IOException) {
                  onLog("Could not start server: ${e.message}")
              } finally {
                  // Clear the starting flag if server failed to start
                  synchronized(this@StreamingServerHelper) {
                      isStarting = false
                  }
              }
            }
        }
    }

    private var bytesSentLastInterval = 0L
    private var lastIntervalTime = System.currentTimeMillis()
    private var currentBps = 0L

    fun addBytesSent(bytes: Int) {
        bytesSentLastInterval += bytes
        val now = System.currentTimeMillis()
        if (now - lastIntervalTime >= 2000) { // Calculate every 2 seconds
            currentBps = (bytesSentLastInterval * 1000) / (now - lastIntervalTime)
            bytesSentLastInterval = 0
            lastIntervalTime = now
        }
    }

    private fun getNetworkPressure(): Int {
        val clientsCount = clients.size
        if (clientsCount == 0) return 0
        
        // Simple pressure heuristic: 
        val mbps = currentBps * 8 / 1024 / 1024
        // 25 Mbps = 100% pressure for a single stream (more realistic for mobile MJPEG)
        val pressure = (mbps * 4).toInt() 
        return pressure.coerceIn(0, 100)
    }

    private suspend fun handleClientConnection(socket: Socket, clientIp: String) {
        try {
            val outputStream = socket.getOutputStream()
            val writer = PrintWriter(outputStream, true)

            // Configure socket timeouts for security
            socket.soTimeout = SOCKET_TIMEOUT_MS

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // Read the request line (e.g., GET /stream HTTP/1.1)
            val requestLine = reader.readLine() ?: return
            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) return
            val uri = requestParts[1]

            val secureStorage = SecureStorage(context)
            val rawUsername = secureStorage.getSecureString(SecureStorage.KEY_USERNAME, "") ?: ""
            val rawPassword = secureStorage.getSecureString(SecureStorage.KEY_PASSWORD, "") ?: ""

            // SECURITY: Authentication is now MANDATORY for all connections
            // Validate stored credentials - require both username and password
            val username = InputValidator.validateAndSanitizeUsername(rawUsername)
            val password = InputValidator.validateAndSanitizePassword(rawPassword)

            if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
                // CRITICAL: No valid credentials configured - reject all connections
                recordFailedAttempt(clientIp)
                writer.print("HTTP/1.1 403 Forbidden\r\n")
                writer.print("Content-Type: text/plain\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print("SECURITY ERROR: Authentication credentials not properly configured.\r\n")
                writer.print("Configure username and password in app settings.\r\n")
                writer.flush()
                socket.close()
                onLog("SECURITY: Connection rejected - authentication credentials not configured")
                return
            }

            // Read HTTP headers
            val headers = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                headers.add(line!!)
            }

            // SECURITY: Require Basic Authentication header for all requests
            // Parse headers in a robust, case-insensitive way (RFC 7230: header field names are case-insensitive)
            val authHeaderPair = headers.mapNotNull { hdr ->
                val idx = hdr.indexOf(":")
                if (idx == -1) return@mapNotNull null
                val name = hdr.substring(0, idx).trim()
                val value = hdr.substring(idx + 1).trim()
                name to value
            }.find { (name, value) ->
                name.equals("Authorization", ignoreCase = true) && value.startsWith("Basic ", ignoreCase = true)
            }

            if (authHeaderPair == null) {
                // Rate limiting ONLY applies to unauthenticated requests
                if (isRateLimited(clientIp)) {
                    writer.print("HTTP/1.1 429 Too Many Requests\r\n")
                    writer.print("Retry-After: 30\r\n") // Reduced to 30 seconds for unauthenticated
                    writer.print("Connection: close\r\n\r\n")
                    writer.flush()
                    socket.close()
                    onLog("SECURITY: Rate limited unauthenticated request from $clientIp")
                    Thread.sleep(100)
                    return
                }
                recordFailedAttempt(clientIp)
                writer.print("HTTP/1.1 401 Unauthorized\r\n")
                writer.print("WWW-Authenticate: Basic realm=\"Android IP Camera\"\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print("Unauthorized. Check username and password in the app settings.\r\n")
                writer.flush()
                socket.close()
                return
            }

            val authValue = authHeaderPair.second
            val providedAuthEncoded = authValue.substringAfter("Basic ", "")
            val providedAuth = try {
                val decoded = Base64.decode(providedAuthEncoded, Base64.DEFAULT)
                String(decoded)
            } catch (e: IllegalArgumentException) {
                // Malformed base64
                if (isRateLimited(clientIp)) {
                    writer.print("HTTP/1.1 429 Too Many Requests\r\n")
                    writer.print("Retry-After: 30\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.flush()
                    socket.close()
                    onLog("SECURITY: Rate limited malformed auth attempt from $clientIp")
                    Thread.sleep(100)
                    return
                }
                recordFailedAttempt(clientIp)
                writer.print("HTTP/1.1 401 Unauthorized\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print("Unauthorized. Check username and password in the app settings.\r\n")
                writer.flush()
                socket.close()
                onLog("SECURITY: Failed authentication attempt from $clientIp (malformed base64)")
                return
            }

            if (providedAuth != "$username:$password") {
                // Rate limiting ONLY applies to failed authentication attempts
                if (isRateLimited(clientIp)) {
                    writer.print("HTTP/1.1 429 Too Many Requests\r\n")
                    writer.print("Retry-After: 30\r\n") // Reduced to 30 seconds for failed auth
                    writer.print("Connection: close\r\n\r\n")
                    writer.flush()
                    socket.close()
                    onLog("SECURITY: Rate limited failed auth attempt from $clientIp")
                    Thread.sleep(100)
                    return
                }
                recordFailedAttempt(clientIp)
                writer.print("HTTP/1.1 401 Unauthorized\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print("Unauthorized. Check username and password in the app settings.\r\n")
                writer.flush()
                socket.close()
                onLog("SECURITY: Failed authentication attempt from $clientIp")
                return
            }

            // Handle Control UI
            if (uri == "/" || uri == "") {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val service = context as? StreamingService
                val curCamera = prefs.getString("last_camera_facing", "back") ?: "back"
                
                // Helper to get lens-specific prefs
                fun getPref(base: String, default: String): String = 
                    service?.let { prefs.getString(it.getLensPrefKey(base), default) } ?: prefs.getString(base, default) ?: default
                
                fun getPrefFloat(base: String, default: Float): Float = 
                    service?.let { prefs.getFloat(it.getLensPrefKey(base), default) } ?: prefs.getFloat(base, default)
                
                fun getPrefInt(base: String, default: Int): Int = 
                    service?.let { prefs.getInt(it.getLensPrefKey(base), default) } ?: prefs.getInt(base, default)
                
                fun getPrefLong(base: String, default: Long): Long = 
                    service?.let { prefs.getLong(it.getLensPrefKey(base), default) } ?: prefs.getLong(base, default)

                val curResolution = getPref("camera_resolution", "low")
                val curZoom = getPref("camera_zoom", "1.0")
                val curScale = getPref("stream_scale", "1.0")
                val curExposure = getPref("camera_exposure", "0")
                val curContrast = getPref("camera_contrast", "0")
                val curDelay = getPref("stream_delay", "33")
                val curTorch = prefs.getString("camera_torch", "off") ?: "off"
                val curTorchStrength = getPref("camera_torch_strength", "1").toIntOrNull() ?: 1
                val maxTorchStrength = prefs.getInt("camera_torch_max_level", 1)
                val curZoomFocusX = getPrefFloat("camera_zoom_focus_x", 0.5f)
                val curZoomFocusY = getPrefFloat("camera_zoom_focus_y", 0.5f)
                val curLensId = prefs.getString("camera_lens_id", "") ?: ""
                val curFocusMode = getPref("camera_focus_mode", "auto")
                val curFocusDistance = getPrefFloat("camera_focus_distance", 0f)
                val minFocusDistance = prefs.getFloat("camera_min_focus_distance", 0f)
                val curExposureMode = getPref("camera_exposure_mode", "auto")
                val curISO = getPrefInt("camera_iso", 400)
                val curShutter = getPrefLong("camera_shutter", 20000000L)
                val curBinning = service?.let { prefs.getBoolean(it.getLensPrefKey("camera_pixel_binning"), false) } ?: false
                val curPowerSaving = prefs.getBoolean("power_saving_mode", false)
                val isoMin = prefs.getInt("camera_iso_min", 100)
                val isoMax = prefs.getInt("camera_iso_max", 3200)
                val shutterMin = prefs.getLong("camera_shutter_min", 100000L)
                val shutterMax = prefs.getLong("camera_shutter_max", 1000000000L)

                // Get lens list from service
                val lenses = service?.getBackLenses() ?: emptyList()
                val lensesHtml = StringBuilder("<option value=''>Auto / Logical</option>")
                lenses.forEach { (id, label) ->
                    val selected = if (id == curLensId) "selected" else ""
                    lensesHtml.append("<option value='$id' $selected>$label</option>")
                }

                val htmlTemplate = try {
                    context.assets.open("index.html").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    "<html><body>Error loading interface.</body></html>"
                }

                val htmlResponse = htmlTemplate
                    .replace("{{CUR_CAMERA}}", curCamera)
                    .replace("{{POWERSAVING_LABEL}}", if (curPowerSaving) "Saving" else "Normal")
                    .replace("{{PS_CLASS}}", if (curPowerSaving) "ps-saving" else "ps-normal")
                    .replace("{{PS_ACTIVE}}", if (curPowerSaving) "active" else "")
                    .replace("{{POWERSAVING_BTN_LABEL}}", if (curPowerSaving) "Disable Power Saving" else "Enable Power Saving")
                    .replace("{{RES_LOW_SELECTED}}", if (curResolution == "low") "selected" else "")
                    .replace("{{RES_MEDIUM_SELECTED}}", if (curResolution == "medium") "selected" else "")
                    .replace("{{RES_HIGH_SELECTED}}", if (curResolution == "high") "selected" else "")
                    .replace("{{RES_4K_SELECTED}}", if (curResolution == "4k") "selected" else "")
                    .replace("{{RES_1080_SELECTED}}", if (curResolution == "1080p") "selected" else "")
                    .replace("{{RES_720_SELECTED}}", if (curResolution == "720p") "selected" else "")
                    .replace("{{RES_480_SELECTED}}", if (curResolution == "480p") "selected" else "")
                    .replace("{{RES_240_SELECTED}}", if (curResolution == "240p") "selected" else "")
                    .replace("{{CUR_ZOOM}}", curZoom)
                    .replace("{{CUR_SCALE}}", curScale)
                    .replace("{{CUR_EXPOSURE}}", curExposure)
                    .replace("{{CUR_CONTRAST}}", curContrast)
                    .replace("{{CUR_DELAY}}", curDelay)
                    .replace("{{CUR_TORCH}}", curTorch)
                    .replace("{{CUR_TORCH_STRENGTH}}", curTorchStrength.toString())
                    .replace("{{MAX_TORCH_STRENGTH}}", maxTorchStrength.toString())
                    .replace("{{CUR_ZOOM_FOCUS_X}}", curZoomFocusX.toString())
                    .replace("{{CUR_ZOOM_FOCUS_Y}}", curZoomFocusY.toString())
                    .replace("{{BACK_LENSES_OPTIONS}}", lensesHtml.toString())
                    .replace("{{CUR_FOCUS_MODE}}", curFocusMode)
                    .replace("{{CUR_FOCUS_DISTANCE}}", curFocusDistance.toString())
                    .replace("{{MIN_FOCUS_DISTANCE}}", minFocusDistance.toString())
                    .replace("{{AF_ACTIVE}}", if (curFocusMode == "auto") "active" else "")
                    .replace("{{MF_ACTIVE}}", if (curFocusMode == "manual") "active" else "")
                    .replace("{{MF_DISPLAY}}", if (curFocusMode == "manual") "block" else "none")
                    .replace("{{CUR_EXPOSURE_MODE}}", curExposureMode)
                    .replace("{{CUR_ISO}}", curISO.toString())
                    .replace("{{CUR_SHUTTER}}", curShutter.toString())
                    .replace("{{CUR_BINNING}}", if (curBinning) "on" else "off")
                    .replace("{{ISO_MIN}}", isoMin.toString())
                    .replace("{{ISO_MAX}}", isoMax.toString())
                    .replace("{{SHUTTER_MIN}}", shutterMin.toString())
                    .replace("{{SHUTTER_MAX}}", shutterMax.toString())
                    .replace("{{AE_ACTIVE}}", if (curExposureMode == "auto") "active" else "")
                    .replace("{{ME_ACTIVE}}", if (curExposureMode == "manual") "active" else "")
                    .replace("{{ME_DISPLAY}}", if (curExposureMode == "manual") "block" else "none")

                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: text/html\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print(htmlResponse)
                writer.flush()
                socket.close()
                return
            }

            // Handle Snapshots (must be before generic query check)
            if (uri.startsWith("/snapshot.jpg")) {
                val service = context as? StreamingService
                val snapshot = if (service != null) {
                    try {
                        withTimeout(5000) { // 5 second timeout for high-res capture
                            suspendCoroutine<ByteArray?> { continuation ->
                                service.takeHighResSnapshot { result ->
                                    continuation.resume(result)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        onLog("Snapshot timeout or error: ${e.message}")
                        service.getLatestSnapshot() // Fallback to low-res if high-res fails/times out
                    }
                } else {
                    null
                }

                if (snapshot != null && snapshot.isNotEmpty()) {
                    val headerStr = "HTTP/1.1 200 OK\r\n" +
                                   "Content-Type: image/jpeg\r\n" +
                                   "Content-Length: ${snapshot.size}\r\n" +
                                   "Content-Disposition: attachment; filename=\"snapshot_${System.currentTimeMillis()}.jpg\"\r\n" +
                                   "Connection: close\r\n\r\n"
                    outputStream.write(headerStr.toByteArray(Charsets.UTF_8))
                    outputStream.write(snapshot)
                    outputStream.flush()
                    // Delay before closing to ensure buffers are fully sent
                    Thread.sleep(100)
                } else {
                    val error = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                    outputStream.write(error.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }
                try { socket.close() } catch (_: Exception) {}
                return
            }

            // Handle Device Status
            if (uri.startsWith("/status")) {
                val status = (context as? StreamingService)?.getDeviceStatus() ?: emptyMap()
                val json = StringBuilder("{")
                status.forEach { (k, v) -> 
                    val valueStr = if (v is String) "\"$v\"" else v.toString()
                    json.append("\"$k\":$valueStr,") 
                }
                // Add network pressure
                json.append("\"pressure\":${getNetworkPressure()},")
                val bitrateMbps = currentBps * 8 / 1024 / 1024.0
                json.append("\"bitrate\":\"${"%.1f".format(bitrateMbps)} Mbps\"")
                json.append("}")
                
                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: application/json\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print(json.toString())
                writer.flush()
                try { socket.close() } catch (_: Exception) {}
                return
            }

            // Handle generic control commands via query params
            if (uri.contains("?")) {
                val query = uri.substringAfter("?")
                query.split("&").forEach { param ->
                    val keyValue = param.split("=")
                    if (keyValue.size == 2) {
                        onControlCommand(keyValue[0], keyValue[1])
                    }
                }
                writer.print("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nOK")
                writer.flush()
                socket.close()
                return
            }

            if (uri.startsWith("/audio")) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    writer.print("HTTP/1.1 403 Forbidden\r\n")
                    writer.print("Content-Type: text/plain\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.print("Microphone permission not granted.\r\n")
                    writer.flush()
                    try { socket.close() } catch (_: Exception) {}
                    return
                }
                try {
                    writer.print("HTTP/1.1 200 OK\r\n")
                    writer.print("Connection: keep-alive\r\n")
                    writer.print("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                    writer.print("Pragma: no-cache\r\n")
                    writer.print("Expires: 0\r\n")
                    writer.print("Content-Type: audio/wav\r\n")
                    writer.print("Transfer-Encoding: chunked\r\n\r\n")
                    writer.flush()

                    val sampleRate = 44100
                    val channelConfig = AudioFormat.CHANNEL_IN_MONO
                    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                    val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                    if (minBuffer <= 0) {
                        throw IOException("Invalid AudioRecord buffer size: $minBuffer")
                    }
                    val bufferSize = minBuffer * 2
                    val audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )

                    val wavHeader = createWavHeader(
                        sampleRate = sampleRate,
                        bitsPerSample = 16,
                        channels = 1
                    )
                    writeChunk(outputStream, wavHeader, wavHeader.size)

                    audioRecord.startRecording()
                    val pcmBuffer = ByteArray(bufferSize)
                    try {
                        while (socket.isConnected && !socket.isClosed) {
                            val read = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                            if (read <= 0) continue
                            writeChunk(outputStream, pcmBuffer, read)
                        }
                    } finally {
                        try {
                            audioRecord.stop()
                        } catch (_: Exception) {
                        }
                        audioRecord.release()
                        try {
                            outputStream.write("0\r\n\r\n".toByteArray())
                            outputStream.flush()
                        } catch (_: Exception) {
                        }
                        try {
                            socket.close()
                        } catch (_: Exception) {
                        }
                    }
                } catch (sec: SecurityException) {
                    writer.print("HTTP/1.1 403 Forbidden\r\n")
                    writer.print("Content-Type: text/plain\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.print("Microphone permission not granted.\r\n")
                    writer.flush()
                    try { socket.close() } catch (_: Exception) {}
                } catch (e: Exception) {
                    onLog("Audio stream error: ${e.message}")
                    try {
                        writer.print("HTTP/1.1 500 Internal Server Error\r\n")
                        writer.print("Content-Type: text/plain\r\n")
                        writer.print("Connection: close\r\n\r\n")
                        writer.print("Audio streaming failed.\r\n")
                        writer.flush()
                    } catch (_: Exception) {
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
                return
            }

            if (uri.startsWith("/stream")) {
                // AUTHENTICATED CONNECTION - No rate limiting, higher connection limits
                if (handleMaxClients(socket, isAuthenticated = true)) return

                // Send HTTP response headers for MJPEG stream
                // Use HTTP/1.1 with keep-alive for better streaming performance
                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Connection: keep-alive\r\n")
                writer.print("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                writer.print("Pragma: no-cache\r\n")
                writer.print("Expires: 0\r\n")
                writer.print("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n")
                writer.flush()

                // Add client to list - frames will be sent from MainActivity.processImage()
                clients.add(Client(socket, outputStream, writer, System.currentTimeMillis(), isAuthenticated = true))
                onClientConnected()

                // Keep connection alive - frames will be sent from MainActivity.processImage()
                // Wait for connection to close or be removed
                try {
                    // Read from socket to detect when client disconnects
                    while (socket.isConnected && !socket.isClosed) {
                        // Check if socket has data (client disconnect will cause exception)
                        if (reader.ready()) {
                            val line = reader.readLine()
                            if (line == null) break // Client disconnected
                        }
                        Thread.sleep(1000) // Check every second
                    }
                } catch (e: IOException) {
                    // Client disconnected
                } finally {
                    // Remove client when connection closes
                    clients.removeIf { it.socket == socket }
                    try {
                        socket.close()
                    } catch (e: Exception) {
                        // Ignore
                    }
                    onClientDisconnected()
                }
            } else {
                writer.print("HTTP/1.1 404 Not Found\r\n")
                writer.print("Content-Type: text/plain\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.print("Not Found\r\n")
                writer.flush()
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            onLog("Error handling client connection from $clientIp: ${e.message}")
            try {
                socket.close()
            } catch (closeException: Exception) {
                // Ignore
            }
        }
    }

    private fun createWavHeader(sampleRate: Int, bitsPerSample: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataChunkSize = 0x7FFFFFFF // Placeholder large size for live stream
        val riffChunkSize = 36 + dataChunkSize

        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(riffChunkSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1) // AudioFormat PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign)
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataChunkSize)
        return buffer.array()
    }

    private fun writeChunk(outputStream: OutputStream, data: ByteArray, length: Int) {
        val header = length.toString(16) + "\r\n"
        outputStream.write(header.toByteArray())
        outputStream.write(data, 0, length)
        outputStream.write("\r\n".toByteArray())
        outputStream.flush()
    }

    fun handleMaxClients(socket: Socket, isAuthenticated: Boolean = false): Boolean {
        val maxAllowed = if (isAuthenticated) maxAuthenticatedClients else maxClients
        if (clients.size >= maxAllowed) {
            socket.getOutputStream().writer().use { writer ->
                writer.write("HTTP/1.1 503 Service Unavailable\r\n")
                writer.write("Retry-After: 30\r\n") // 30 seconds
                writer.write("Connection: close\r\n\r\n")
                writer.flush()
            }
            socket.close()
            // Add small delay to prevent rapid reconnection loops
            Thread.sleep(100)
            return true
        }
        return false
    }

    fun setAppInForeground(foreground: Boolean) {
        appInForeground = foreground
    }

    suspend fun stopStreamingServer() {
        val jobToCancel: Job?
        val socketToClose: ServerSocket?

        synchronized(this) {
            // Get references to close outside synchronized block
            jobToCancel = serverJob
            socketToClose = serverSocket
            serverSocket = null
            serverJob = null
        }

        // If there's nothing to stop, return immediately
        if (jobToCancel == null && socketToClose == null) {
            return
        }

        // Run socket closing operations on background thread to avoid NetworkOnMainThreadException
        withContext(Dispatchers.IO) {
            // Close the server socket first to interrupt any blocking accept() calls
            try {
                socketToClose?.close()
            } catch (e: IOException) {
                onLog("Error closing server socket: ${e.message}")
            }

            // Cancel the server coroutine and wait for it to finish
            jobToCancel?.cancel()
            try {
                // Wait for the coroutine to finish
                jobToCancel?.join()
            } catch (e: Exception) {
                onLog("Error waiting for server job: ${e.message}")
            }

            // Close all client connections (this involves network operations)
            closeClientConnection()

            // Wait a bit longer to ensure port is fully released
            try {
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

        }
    }

    fun closeClientConnection() {
        clients.forEach { client ->
            try {
                client.socket.close()
            } catch (e: IOException) {
                onLog("Error closing client connection: ${e.message}")
            }
        }
        clients.clear()
        onClientDisconnected()
    }

    fun removeClient(client: Client) {
        clients.remove(client)
        try {
            client.socket.close()
        } catch (e: IOException) {
            onLog("Error closing client socket: ${e.message}")
        }
        onClientDisconnected()
    }

    private fun cleanupExpiredConnections() {
        val now = System.currentTimeMillis()
        val toRemove = clients.filter { client ->
            val maxDuration = if (client.isAuthenticated) MAX_AUTHENTICATED_CONNECTION_DURATION_MS else MAX_CONNECTION_DURATION_MS
            now - client.connectedAt > maxDuration
        }

        toRemove.forEach { client ->
            val authStatus = if (client.isAuthenticated) "authenticated" else "unauthenticated"
            onLog("Removing expired $authStatus connection from ${client.socket.inetAddress.hostAddress}")
            removeClient(client)
        }
    }
}
