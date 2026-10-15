package com.spatialmemory.recorder

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

/** Camera and encoder are confined to one worker; no unbounded frame queue. */
class CameraStream(
    private val manager: CameraManager, val spec: CameraSpec, private val folder: File, private val epochNs: Long,
    private val onPrepared: () -> Unit, private val onOpened: () -> Unit,
    private val onFailure: (String) -> Unit, private val onFinished: (JSONObject) -> Unit
) {
    private val thread = HandlerThread("Walk-${spec.role}").apply { start() }
    private val handler = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var captures: java.io.BufferedWriter? = null
    private var writer: VideoWriter? = null
    private var opening = false
    private var cameraClosed = true
    private var stopping = false
    private var finished = false
    private var finalStats: JSONObject? = null
    private var captureCount = 0L
    private var captureFailures = 0L
    private var bufferLost = 0L
    @Volatile var encodedFrames = 0L; private set
    @Volatile var lastFrameElapsedNs = 0L; private set

    fun prepare() { handler.post {
        try {
            if (stopping) return@post
            val output = VideoWriter(folder, spec, epochNs)
            writer = output
            output.prepare()
            captures = File(folder, "${spec.role}_captures.jsonl").bufferedWriter()
            reader = ImageReader.newInstance(spec.size.width, spec.size.height, ImageFormat.YUV_420_888, 3).apply {
                setOnImageAvailableListener({ source ->
                    if (!stopping) try {
                        source.acquireLatestImage()?.use { image ->
                            output.write(image, SystemClock.elapsedRealtimeNanos())
                            encodedFrames = output.encoded
                            lastFrameElapsedNs = output.lastOutputElapsedNs
                        }
                    } catch (e: Exception) { fail("${spec.role}: ${e.message}") }
                }, handler)
            }
            onPrepared()
        } catch (e: Exception) { fail("${spec.role}: ${e.message}") }
    } }

    @SuppressLint("MissingPermission")
    fun open() { handler.post {
        if (stopping) return@post
        try {
            opening = true
            manager.openCamera(spec.id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    opening = false; cameraClosed = false; camera = device
                    if (stopping) device.close() else onOpened()
                }
                override fun onDisconnected(device: CameraDevice) {
                    opening = false; cameraClosed = false; camera = device
                    device.close()
                    if (!stopping) fail("${spec.role}: camera disconnected while opening both cameras.")
                }
                override fun onError(device: CameraDevice, error: Int) {
                    opening = false; cameraClosed = false; camera = device
                    device.close()
                    if (!stopping) fail("${spec.role}: camera error $error (camera pair may be unavailable).")
                }
                override fun onClosed(device: CameraDevice) {
                    cameraClosed = true; camera = null; finishIfClosed()
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            fail("${spec.role}: ${e.message}")
        }
    } }

    @Suppress("DEPRECATION")
    fun configure() { handler.post {
        if (stopping) return@post
        try {
            val device = checkNotNull(camera)
            val surface = checkNotNull(reader).surface
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (stopping) { configured.close(); return }
                    session = configured
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            spec.fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        }.build()
                        configured.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                if (stopping) return
                                try {
                                    val row = JSONObject().put("capture_index", captureCount++)
                                        .put("camera_frame_number", result.frameNumber)
                                        .put("sensor_timestamp_ns", result.get(CaptureResult.SENSOR_TIMESTAMP)?.toString() ?: JSONObject.NULL)
                                        .put("exposure_ns", result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.toString() ?: JSONObject.NULL)
                                        .put("frame_duration_ns", result.get(CaptureResult.SENSOR_FRAME_DURATION)?.toString() ?: JSONObject.NULL)
                                        .put("iso", result.get(CaptureResult.SENSOR_SENSITIVITY) ?: JSONObject.NULL)
                                        .put("ae_state", result.get(CaptureResult.CONTROL_AE_STATE) ?: JSONObject.NULL)
                                    captures?.apply { write(row.toString()); newLine(); if (captureCount % 15 == 0L) flush() }
                                } catch (e: Exception) { fail("${spec.role}: capture log: ${e.message}") }
                            }
                            override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                captureFailures++
                            }
                            override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: android.view.Surface, frameNumber: Long) {
                                bufferLost++
                            }
                        }, handler)
                    } catch (e: Exception) { fail("${spec.role}: ${e.message}") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close()
                    if (!stopping) fail("${spec.role}: the camera rejected this recording session.")
                }
            }, handler)
        } catch (e: Exception) { fail("${spec.role}: ${e.message}") }
    } }

    private fun fail(message: String) { onFailure(message) }

    fun stop() { handler.post {
        if (stopping) return@post
        stopping = true
        try { session?.stopRepeating() } catch (_: Exception) { }
        try { session?.abortCaptures() } catch (_: Exception) { }
        session?.close(); session = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close(); reader = null
        camera?.close()
        val stats = writer?.finish() ?: JSONObject().put("complete", false).put("encoded_frames", 0)
        writer = null
        try { captures?.close() } catch (e: Exception) { stats.put("complete", false).put("error", e.message) }
        captures = null
        stats.put("capture_results", captureCount).put("capture_failures", captureFailures).put("capture_buffers_lost", bufferLost)
        finalStats = spec.json().put("result", stats)
        finishIfClosed()
    } }

    private fun finishIfClosed() {
        if (!finished && stopping && !opening && cameraClosed && finalStats != null) {
            finished = true
            onFinished(checkNotNull(finalStats))
            thread.quitSafely()
        }
    }
}
