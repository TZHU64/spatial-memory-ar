package com.spatialmemory.demo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.WindowManager
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** A single, bounded JPEG capture. All callbacks and resource ownership use the main looper. */
class StillCapture(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var closed = false
    private var afterClose: (() -> Unit)? = null
    private var completion: ((Result<File>) -> Unit)? = null
    private val timeout = Runnable { finish(Result.failure(Exception("Camera timed out. Try again."))) }

    @SuppressLint("MissingPermission")
    fun capture(callback: (Result<File>) -> Unit) {
        completion = callback
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            val candidates = manager.cameraIdList.mapNotNull { id ->
                val c = manager.getCameraCharacteristics(id)
                val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)
                Log.i("SpatialCapture", "Camera $id JPEG sizes: ${sizes?.joinToString()}")
                if (sizes.isNullOrEmpty() || c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO) null
                else Triple(id, c, sizes.toList())
            }
            val candidate = candidates.maxByOrNull { it.third.maxOf { s -> s.width.toLong() * s.height } }
                ?: error("No color camera with JPEG output is available.")
            val size = candidate.third.filter { it.width * it.height <= 1920 * 1080 }
                .maxByOrNull { it.width * it.height } ?: candidate.third.minByOrNull { it.width * it.height }!!
            val rotation = context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
            val degrees = when (rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            val orientation = ((candidate.second.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) - degrees + 360) % 360
            val previewSize = candidate.second.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(SurfaceTexture::class.java).minByOrNull { it.width * it.height }!!
            val texture = SurfaceTexture(false).apply { setDefaultBufferSize(previewSize.width, previewSize.height) }
            previewTexture = texture
            val preview = Surface(texture)
            previewSurface = preview
            Log.i("SpatialCapture", "Selected ${candidate.first}: $size sensor orientation=$orientation")
            val output = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            reader = output
            output.setOnImageAvailableListener({ source ->
                if (!closed) {
                    try {
                        val image = source.acquireLatestImage()
                        if (image != null) {
                            val wall = Instant.now().toString()
                            val elapsed = android.os.SystemClock.elapsedRealtime()
                            val timestamp = image.timestamp
                            val bytes = image.use { img -> ByteArray(img.planes[0].buffer.remaining()).also { img.planes[0].buffer.get(it) } }
                            val directory = File(context.filesDir, "captures").apply { mkdirs() }
                            val file = File(directory, "${UUID.randomUUID()}.jpg")
                            file.writeBytes(bytes)
                            val metadata = JSONObject().put("captured_at", wall).put("capture_time_quality", "approximate")
                                .put("capture_elapsed_ms", elapsed).put("sensor_timestamp_ns", timestamp).put("camera_id", candidate.first)
                                .put("jpeg_orientation", orientation).put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                            File(directory, file.nameWithoutExtension + ".json").writeText(metadata.toString(2))
                            Log.i("SpatialCapture", "Saved ${file.name} (${bytes.size} bytes)")
                            finish(Result.success(file))
                        }
                    } catch (e: Exception) { finish(Result.failure(e)) }
                }
            }, handler)
            handler.postDelayed(timeout, 12000)
            manager.openCamera(candidate.first, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) { camera.close(); return }
                    device = camera
                    try {
                        camera.createCaptureSession(listOf(output.surface, preview), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                if (closed) { configured.close(); return }
                                session = configured
                                try {
                                    val warmup = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(preview) }.build()
                                    configured.setRepeatingRequest(warmup, null, handler)
                                    handler.postDelayed({
                                    if (!closed) try {
                                    configured.stopRepeating()
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(output.surface)
                                        set(CaptureRequest.JPEG_ORIENTATION, orientation)
                                        set(CaptureRequest.JPEG_QUALITY, 90.toByte())
                                    }.build()
                                    configured.capture(request, object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                                            finish(Result.failure(Exception("Camera capture failed: ${f.reason}")))
                                        }
                                    }, handler)
                                    } catch (e: Exception) { finish(Result.failure(e)) }
                                    }, 1000)
                                } catch (e: Exception) { finish(Result.failure(e)) }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); finish(Result.failure(Exception("JPEG configuration failed"))) }
                        }, handler)
                    } catch (e: Exception) { finish(Result.failure(e)) }
                }
                override fun onClosed(camera: CameraDevice) { afterClose?.also { afterClose = null; it() } }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); finish(Result.failure(Exception("Camera disconnected"))) }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); finish(Result.failure(Exception("Camera error $error"))) }
            }, handler)
        } catch (e: Exception) { finish(Result.failure(e)) }
    }
    private fun finish(result: Result<File>) {
        if (closed) return
        val callback = completion
        close { callback?.invoke(result) }
    }
    fun close(onClosed: (() -> Unit)? = null) {
        closed = true; completion = null; handler.removeCallbacks(timeout)
        session?.close(); session = null
        val closingDevice = device
        afterClose = onClosed
        closingDevice?.close(); device = null
        reader?.close(); reader = null
        previewSurface?.release(); previewSurface = null
        previewTexture?.release(); previewTexture = null
        if (closingDevice == null) { afterClose = null; onClosed?.invoke() }
    }
}
