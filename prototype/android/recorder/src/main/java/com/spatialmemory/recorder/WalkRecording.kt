package com.spatialmemory.recorder

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.*
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Coordinates open-all-then-configure camera startup and a common recording timeline. */
class WalkRecording(
    context: Context, private val mode: Mode,
    private val onUpdate: (String, String) -> Unit, private val onFinished: (String, String) -> Unit
) {
    enum class Mode { DUAL, VGA, RGB }
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val epochNs = SystemClock.elapsedRealtimeNanos()
    private val wallMs = System.currentTimeMillis()
    private val runId = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochMilli(wallMs)) + "_" + UUID.randomUUID().toString().take(8)
    private var folder: File? = null
    private var manifest = JSONObject()
    private val streams = mutableListOf<CameraStream>()
    private val results = mutableListOf<JSONObject>()
    private val events = JSONArray()
    private var prepared = 0
    private var opened = 0
    private var stopping = false
    private var finished = false
    private var reason = ""
    private var failure: String? = null
    private var allFramesStarted = false
    private var startedNs = 0L
    private var advertised = false
    private var markNumber = 0
    private val tick = object : Runnable {
        override fun run() {
            if (stopping || finished) return
            try {
                val now = SystemClock.elapsedRealtimeNanos()
                if (!allFramesStarted && streams.isNotEmpty() && streams.all { it.encodedFrames >= 2 }) {
                    allFramesStarted = true
                    startedNs = now
                    manifest.put("state", "recording").put("all_streams_started_elapsed_ns", now.toString())
                        .put("both_streams_observed", mode == Mode.DUAL)
                    event("all_streams_started")
                    saveManifest()
                }
                if (!allFramesStarted && now - epochNs > 15_000_000_000L) {
                    fail("Both requested streams did not start. This firmware may not support the camera pair."); return
                }
                if (allFramesStarted && streams.any { now - it.lastFrameElapsedNs > 5_000_000_000L }) {
                    fail("A requested camera stopped producing video. The run is incomplete."); return
                }
                val duration = if (startedNs == 0L) 0 else (now - startedNs) / 1_000_000_000
                if (now - epochNs >= 600_000_000_000L) { stop("Ten-minute limit reached"); return }
                if (folder != null && checkNotNull(folder).usableSpace < 512L * 1024 * 1024) {
                    stop("Storage reserve reached"); return
                }
                val power = context.getSystemService(PowerManager::class.java)
                if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                    stop("Glasses became too warm"); return
                }
                val counts = streams.joinToString(" · ") { "${it.spec.role.uppercase()} ${it.encodedFrames}" }
                val time = "%02d:%02d".format(duration / 60, duration % 60)
                onUpdate(if (allFramesStarted) "Recording $time" else "Starting cameras…",
                    "$counts\n${if (mode == Mode.DUAL) "VGA + RGB" else mode.name} · local videos\nDouble tap to stop and save.")
                main.postDelayed(this, 1000)
            } catch (e: Exception) { fail(e.message ?: "Recorder check failed.") }
        }
    }

    fun start() {
        try {
            val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: error("Recording storage unavailable.")
            val dir = File(root, "WalkRecorder/$runId")
            check(dir.mkdirs()) { "Could not create recording folder." }
            folder = dir
            check(dir.usableSpace >= 1024L * 1024 * 1024) { "At least 1 GB free space is needed." }
            val manager = context.getSystemService(CameraManager::class.java)
            val inventory = CameraInventory(manager)
            val specs = inventory.specs(mode)
            advertised = inventory.concurrentSets.any { group -> specs.all { it.id in group } }
            manifest = JSONObject().put("schema_version", 1).put("run_id", runId).put("state", "starting")
                .put("app_version", BuildConfig.VERSION_NAME).put("mode", mode.name.lowercase())
                .put("device_model", Build.MODEL).put("firmware", Build.FINGERPRINT)
                .put("start_device_wall_ms", wallMs).put("start_elapsed_ns", epochNs.toString())
                .put("wall_clock_quality", "unverified_device_clock")
                .put("camera_inventory", inventory.json()).put("pair_advertised", advertised)
                .put("both_streams_observed", false).put("hardware_frame_sync", false)
                .put("cross_camera_sensor_clock_comparable", specs.all {
                    it.timestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME })
                .put("timing_notes", "MP4 timestamps start at each camera's first acquired frame. Align with frames JSONL session_elapsed_ns. UNKNOWN clocks use approximate callback anchors; visual synchronization is required.")
                .put("max_duration_seconds", 600).put("storage_reserve_bytes", 512L * 1024 * 1024)
                .put("audio_recorded", false).put("network_upload", false)
                .put("streams", JSONArray(specs.map { it.json() })).put("start_battery", battery())
            event("start_requested")
            saveManifest()
            specs.forEach { spec ->
                streams += CameraStream(manager, spec, dir, epochNs,
                    onPrepared = { main.post {
                        if (!stopping && ++prepared == specs.size) streams.first().open()
                    } },
                    onOpened = { main.post {
                        // Open every camera before configuring any capture session.
                        if (!stopping) {
                            opened++
                            if (opened < streams.size) streams[opened].open()
                            else streams.forEach { it.configure() }
                        }
                    } },
                    onFailure = { message -> main.post { fail(message) } },
                    onFinished = { result -> main.post {
                        results += result
                        if (results.size == streams.size) finish()
                    } })
            }
            streams.forEach { it.prepare() }
            main.post(tick)
        } catch (e: Exception) { fail(e.message ?: "Recording could not start.") }
    }

    fun mark() {
        if (stopping || finished) return
        markNumber++
        event("wearer_marker", "marker_$markNumber")
        try { saveManifest() } catch (e: Exception) { fail("Could not save marker: ${e.message}") }
        onUpdate("Marker $markNumber saved", "Continue walking.\nDouble tap to stop and save.")
    }

    private fun fail(message: String) {
        if (stopping || finished) return
        failure = message
        Log.e("WalkRecorder", message)
        stop("Recording failed")
    }

    fun stop(stopReason: String) {
        if (stopping || finished) return
        stopping = true; reason = stopReason
        main.removeCallbacks(tick)
        onUpdate("Saving videos…", "Keep the app open for a moment.")
        event("stop_requested", stopReason)
        manifest.put("state", "stopping")
        try { saveManifest() } catch (e: Exception) { if (failure == null) failure = e.message }
        if (streams.isEmpty()) finish() else streams.forEach { it.stop() }
    }

    private fun finish() {
        if (finished) return
        finished = true
        val windows = results.mapNotNull {
            val stats = it.getJSONObject("result")
            val first = stats.optString("first_encoded_session_ns").toLongOrNull()
            val last = stats.optString("last_encoded_session_ns").toLongOrNull()
            if (first != null && last != null) first to last else null
        }
        val overlap = if (windows.size == streams.size && windows.isNotEmpty())
            ((windows.minOf { it.second } - windows.maxOf { it.first }) / 1e9).coerceAtLeast(0.0) else 0.0
        val complete = failure == null && allFramesStarted && overlap > 0 && results.size == streams.size &&
            results.all { it.getJSONObject("result").optBoolean("complete") }
        manifest.put("state", if (complete) "complete" else "incomplete")
            .put("stop_reason", reason).put("error", failure ?: JSONObject.NULL)
            .put("end_elapsed_ns", SystemClock.elapsedRealtimeNanos().toString())
            .put("end_device_wall_ms", System.currentTimeMillis()).put("end_battery", battery())
            .put("overlap_seconds", overlap)
            .put("streams", JSONArray(results))
        event("finished")
        try { saveManifest() } catch (e: Exception) { failure = "Manifest save failed: ${e.message}" }
        val valid = complete && failure == null
        Log.i("WalkRecorder", "Run $runId ${if (valid) "complete" else "incomplete"}: ${folder?.path}; $failure")
        val problem = failure ?: results.firstOrNull { !it.getJSONObject("result").optBoolean("complete") }
            ?.getJSONObject("result")?.optString("error") ?: "A requested stream did not produce a complete video."
        onFinished(if (valid) "Videos saved" else "Recording incomplete",
            if (valid) "$runId\n${results.joinToString(" · ") { it.getString("role").uppercase() + " " + it.getJSONObject("result").optLong("encoded_frames") + " frames" }}\nConnect USB to copy this run."
            else "$problem\nDiagnostics saved: $runId\nTry a single-camera mode if needed.")
    }

    private fun event(type: String, label: String? = null) {
        events.put(JSONObject().put("type", type).put("elapsed_ns", SystemClock.elapsedRealtimeNanos().toString())
            .put("label", label ?: JSONObject.NULL))
    }

    private fun saveManifest() {
        val dir = folder ?: return
        manifest.put("events", events)
        val atomic = android.util.AtomicFile(File(dir, "manifest.json"))
        val output = atomic.startWrite()
        try { output.write(manifest.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    private fun battery(): JSONObject {
        val info = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().put("level", info?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
            .put("scale", info?.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
            .put("temperature_tenths_c", info?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1))
    }
}
