package com.spatialmemory.demo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** VGA luminance is analyzed locally and immediately discarded; only RGB stills are saved. */
class VgaMonitor(private val context: Context, private val gate: LayeredSceneGate) {
    private val handler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("LocalVgaModel").apply { start() }
    private val worker = Handler(workerThread.looper)
    private var embedder: VgaEmbedder? = null
    private var deliveryPending = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var closed = false
    private var opening = false
    private var lastSample = 0L
    private var closeCallback: (() -> Unit)? = null
    var concurrentRgb = false
        private set
    private var failure: ((String) -> Unit)? = null
    private val watchdog = Runnable { fail("VGA stream stopped. Select Start / Resume.") }

    private fun fail(message: String) { if (!closed) { val callback=failure; close(); callback?.invoke(message) } }

    @SuppressLint("MissingPermission")
    fun start(onFrame: (Array<FloatArray>?, Long) -> Unit, onFailure: (String) -> Unit) {
        failure=onFailure
        worker.post {
            try { if (!closed) embedder = VgaEmbedder(context.applicationContext) }
            catch (_: Exception) { handler.post { fail("Local vision model unavailable. Select Remember view manually after reconnecting.") } }
        }
        try {
            val manager=context.getSystemService(CameraManager::class.java)
            val characteristics=manager.cameraIdList.associateWith { manager.getCameraCharacteristics(it) }
            val mono=characteristics.entries.filter { it.value.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)==CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO }
                .singleOrNull() ?: error("An unambiguous monochrome VGA camera is required.")
            val sizes=mono.value.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
                ?: error("VGA YUV capture is unavailable.")
            val size=sizes.filter { it.width<=640 && it.height<=480 }.maxByOrNull { it.width*it.height }
                ?: error("No supported VGA-size YUV stream.")
            val rgb=characteristics.entries.filter { it.value.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)!=CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO }
                .maxByOrNull { it.value.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { s -> s.width*s.height } ?: 0 }?.key
            concurrentRgb=rgb!=null && manager.concurrentCameraIds.any { it.contains(mono.key) && it.contains(rgb) }
            Log.i("SpatialVga", "VGA=${mono.key} size=$size RGB=$rgb advertisedConcurrent=$concurrentRgb sets=${manager.concurrentCameraIds}")
            val output=ImageReader.newInstance(size.width,size.height,ImageFormat.YUV_420_888,2)
            reader=output
            val comparableClock = mono.value.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            output.setOnImageAvailableListener({ source ->
                if (!closed) try {
                    var frame: VgaFrame? = null
                    var frameTime = 0L
                    source.acquireLatestImage()?.use { image ->
                        handler.removeCallbacks(watchdog); handler.postDelayed(watchdog,5000)
                        val now = SystemClock.elapsedRealtime()
                        if (!deliveryPending && now-lastSample >= 167) {
                            lastSample = now
                            frameTime = if (comparableClock) image.timestamp/1_000_000 else now
                            val y = image.planes[0]; val crop = image.cropRect
                            frame = VgaFrame(y.buffer, y.rowStride, y.pixelStride,
                                crop.left, crop.top, crop.width(), crop.height())
                        }
                    } // Release camera Image before neural inference.
                    val candidate = frame
                    if (!closed && candidate != null) {
                        val started = SystemClock.elapsedRealtime()
                        val features = if (gate.shouldEmbed(candidate.grid, candidate.sharpness))
                            checkNotNull(embedder) { "VGA model not ready" }.encode(candidate) else null
                        if (features != null) Log.d("SpatialVga", "S1 five crops ms=${SystemClock.elapsedRealtime()-started}")
                        deliveryPending = true
                        handler.post {
                            try { if (!closed) onFrame(features, frameTime) }
                            finally { worker.post { deliveryPending = false } }
                        }
                    }
                } catch (_: Exception) { handler.post { fail("Local vision failed. Stop and Resume to retry.") } }
            },worker)
            handler.postDelayed(watchdog,10000)
            opening = true
            manager.openCamera(mono.key,object:CameraDevice.StateCallback(){
                override fun onOpened(device:CameraDevice) {
                    opening = false
                    camera=device
                    if(closed) { device.close();return }
                    try {
                        device.createCaptureSession(listOf(output.surface),object:CameraCaptureSession.StateCallback(){
                            override fun onConfigured(configured:CameraCaptureSession) {
                                if(closed) { configured.close();return }
                                session=configured
                                try {
                                    val request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(output.surface) }.build()
                                    configured.setRepeatingRequest(request,null,handler)
                                } catch(e:Exception) { fail(e.message ?: "VGA start failed") }
                            }
                            override fun onConfigureFailed(s:CameraCaptureSession) { s.close();fail("VGA stream configuration failed") }
                        },handler)
                    } catch(e:Exception) { fail(e.message ?: "VGA camera failed") }
                }
                override fun onClosed(device:CameraDevice) { camera=null;closeCallback?.also { closeCallback=null;it() } }
                override fun onDisconnected(device:CameraDevice) { opening=false; device.close();fail("VGA disconnected") }
                override fun onError(device:CameraDevice,error:Int) { opening=false; device.close();fail("VGA camera error $error") }
            },handler)
        } catch(e:Exception) { opening=false; fail(e.message ?: "VGA unavailable") }
    }
    fun close(afterClosed: (() -> Unit)? = null) {
        closed=true;failure=null;handler.removeCallbacks(watchdog)
        session?.close();session=null
        val output = reader; reader = null
        worker.post { output?.close(); embedder?.close(); embedder = null; workerThread.quitSafely() }
        val device=camera
        closeCallback=afterClosed
        if(device!=null) device.close() else if (!opening) { closeCallback=null;afterClosed?.invoke() }
    }
}
