package com.spatialmemory.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.spatialmemory.demo.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {
    private var selected = 0
    private var active = false
    private var busy = false
    private var generation = 0
    private var camera: StillCapture? = null
    private var title = "Paused"
    private var description = "Start memory or select Ask by voice."
    private var sessionId: String? = null
    private val memory by lazy { DeviceMemory(applicationContext) }
    private var voice: VoiceQuestionRecorder? = null
    private var questionGeneration = 0
    private var questionStage = "idle"
    private var transcript = ""
    private var answerPages = emptyList<String>()
    private var answerPage = 0
    private var monitor: VgaMonitor? = null
    private var gate = LayeredSceneGate()
    private var automatic = false
    private var switchingCamera = false
    private var detectorStatus = ""
    private var lastDetectorUpdate = 0L

    private fun startMonitoring() {
        if (!active || !automatic || monitor != null) return
        gate.streamRestarted()
        val watching = VgaMonitor(this, gate)
        monitor = watching
        watching.start(onFrame = { features, time ->
            if (active && automatic && monitor === watching) {
                val trigger = features != null && gate.sample(features, time, !busy && MemoryWorker.hasCapacity())
                if (time - lastDetectorUpdate >= 1000 || trigger) {
                    lastDetectorUpdate = time
                    detectorStatus = if (busy) "RGB capture in progress" else if (!MemoryWorker.hasCapacity())
                        "Photo queue full; waiting for space" else gate.status()
                    Log.i("SpatialVga", "Detector: $detectorStatus; ${gate.diagnostics(time)}; ${MemoryWorker.status()}")
                    render()
                }
                if (trigger) {
                    Log.i("SpatialVga", "Persistent local novelty triggers RGB")
                    capture(true)
                }
            }
        }, onFailure = { message ->
            if (monitor === watching) {
                monitor = null; automatic = false; gate.complete(false)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                title = "Automatic memory paused"
                description = message + "\nRemember view is still available."
                selected = 1
                render()
            }
        })
    }

    private fun labels() = listOf("Start / Resume", "Remember view", "Ask by voice", "Stop")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    when (action) {
                        is TempleAction.Click -> select()
                        is TempleAction.DoubleClick -> pauseCapture()
                        is TempleAction.SlideForward, is TempleAction.SlideDownwards -> move(1)
                        is TempleAction.SlideBackward, is TempleAction.SlideUpwards -> move(-1)
                        else -> Unit
                    }
                }
            }
        }
    }
    private fun move(delta: Int) {
        if (questionStage in listOf("answer", "ready") && answerPages.size > 1) {
            answerPage = (answerPage + delta + answerPages.size) % answerPages.size
            if (questionStage == "ready") showTranscriptPage() else showAnswerPage()
            return
        }
        selected = (selected + delta + 4) % 4; render()
    }
    private fun render() {
        mBindingPair.updateView {
            status.text = title
            detail.text = description + (if (questionStage == "idle")
                (if (automatic) "\n" + detectorStatus else "") + "\n" + MemoryWorker.status() else "")
            actions.text = if (questionStage != "idle") "Double tap: cancel / stop" else labels().mapIndexed { i, label ->
                (if (i == selected) "› " else "  ") + label + (if (i == 1 && busy) " (busy)" else "")
            }.joinToString("\n")
        }
    }
    private fun select() {
        if (questionStage == "recording") { voice?.stop(); title = "Finishing recording…"; render(); return }
        if (questionStage in listOf("transcribing", "answering")) return
        if (questionStage == "ready") { submitQuestion(); return }
        if (questionStage == "answer") { questionStage = "idle"; answerPages = emptyList(); selected = 2 }
        when (selected) {
            0 -> connect()
            1 -> capture(false)
            2 -> recall()
            3 -> pauseCapture()
        }
    }
    private fun connect() {
        if (busy || automatic) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1); return
        }
        if (!DirectMimo.configured()) {
            title = "MiMo key missing"; description = "Configure the private demo key and rebuild."; render(); return
        }
        ++generation
        sessionId = UUID.randomUUID().toString()
        MemoryWorker.recoverPending(this)
        active = true; automatic = true; gate = LayeredSceneGate()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        title = "Automatic memory on"
        description = "Memories saved on glasses.\nSelect Ask by voice to ask a question."
        selected = 2
        startMonitoring(); render()
    }
    private fun capture(fromVga: Boolean = false) {
        if (!active || busy) return
        if (!MemoryWorker.hasCapacity()) {
            title = "Analysis queue full"; description = "VGA is watching. Capture resumes as space clears."; render(); return
        }
        val ticket = ++generation
        if (!fromVga && automatic) gate.beginManualCapture()
        val sid = sessionId ?: return
        Log.i("SpatialVga", if (fromVga) "RGB requested by VGA gate" else "RGB requested manually")
        busy = true; title = "Capturing RGB…"; description = "Keep your head steady."; render()
        val launchRgb = {
        if (ticket == generation && active) {
        camera = StillCapture(this)
        camera!!.capture captured@ { result ->
            camera = null
            if (ticket != generation || !active) { busy = false; return@captured }
            startMonitoring()
            result.fold(onSuccess = { file ->
                    try {
                        val local = JSONObject(File(file.parentFile, file.nameWithoutExtension + ".json").readText())
                        val prefs = getSharedPreferences("sequences", MODE_PRIVATE)
                        val sequence = prefs.getLong(sid, 0) + 1
                        check(prefs.edit().putLong(sid, sequence).commit()) { "Could not allocate capture sequence" }
                        val metadata = JSONObject().put("schema_version", "0.6").put("session_id", sid)
                            .put("request_id", UUID.randomUUID().toString()).put("capture_sequence", sequence)
                            .put("capture_mode", if (fromVga) "automatic" else "manual")
                            .put("storage_mode", "device")
                            .put("captured_at", local.getString("captured_at"))
                            .put("time_quality", "approximate").put("sensor_timestamp_ns", local.getLong("sensor_timestamp_ns").toString())
                            .put("image_sha256", local.getString("sha256"))
                        check(MemoryWorker.enqueue(file, metadata)) { "Analysis queue is full. Capture retained locally." }
                        // The gate remembers captured image content, not completion of remote analysis.
                        gate.complete(true)
                        title = if (automatic) "Automatic memory on" else "Photo queued"
                        description = "Watching for new views.\nPhotos are checked in the background."
                    } catch (e: Exception) {
                        gate.complete(false)
                        title = "Could not queue photo"; description = e.message ?: "Local photo retained."
                    } finally { busy = false; render() }
            }, onFailure = {
                gate.complete(false); busy = false; pauseCapture()
                title = "Could not capture"; description = it.message ?: "Select Resume to retry."; render()
            })
        }
        }
        }
        val watching = monitor
        if (watching != null && !watching.concurrentRgb) {
            monitor = null
            switchingCamera = true
            watching.close {
                if (ticket == generation) { switchingCamera = false; launchRgb() }
            }
        } else launchRgb()
    }
    private fun recall() {
        if (busy) return
        if (!DirectMimo.configured()) {
            title = "MiMo key missing"; description = "Configure the private demo key and rebuild."; render(); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2); return
        }
        // Pause new captures while the question card is visible; queued memories can still finish.
        automatic = false; monitor?.close(); monitor = null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ticket = ++questionGeneration
        questionStage = "recording"; selected = 2
        title = "Listening…"
        description = "Speak your question.\nTap to finish · maximum 15 seconds\nDouble tap to cancel."
        val recorder = VoiceQuestionRecorder(this)
        voice = recorder
        render()
        recorder.start { result ->
            if (ticket != questionGeneration) { result.getOrNull()?.delete(); return@start }
            voice = null
            result.fold(onSuccess = { wav ->
                questionStage = "transcribing"; title = "Recognizing speech…"
                description = "MiMo is transcribing your question."; render()
                lifecycleScope.launch {
                    try {
                        val text = withContext(Dispatchers.IO) { try { DirectMimo().transcribe(wav) } finally { wav.delete() } }
                        if (ticket == questionGeneration) {
                            transcript = text; questionStage = "ready"
                            answerPages = text.chunked(150); answerPage = 0
                            showTranscriptPage()
                        }
                    } catch (e: Exception) {
                        if (ticket == questionGeneration) questionFailed(e)
                    } finally { wav.delete() }
                }
            }, onFailure = { questionFailed(it) })
        }
    }
    private fun submitQuestion() {
        val ticket = questionGeneration
        questionStage = "answering"; title = "Searching memories…"
        description = transcript.take(180); render()
        lifecycleScope.launch {
            try {
                val answer = withContext(Dispatchers.IO) { DirectMimo().answer(memory, transcript) }
                if (ticket == questionGeneration) {
                    answerPages = answer.chunked(150); answerPage = 0; questionStage = "answer"
                    showAnswerPage()
                }
            } catch (e: Exception) { if (ticket == questionGeneration) questionFailed(e) }
        }
    }
    private fun showAnswerPage() {
        title = "Memory answer · ${answerPage + 1} / ${answerPages.size}"
        description = answerPages[answerPage] + "\nSwipe: pages · tap: new question"
        render()
    }
    private fun showTranscriptPage() {
        title = "Your question · ${answerPage + 1} / ${answerPages.size}"
        description = answerPages[answerPage] + "\nSwipe: pages · tap: ask"
        render()
    }
    private fun questionFailed(error: Throwable) {
        questionStage = "idle"; title = "Could not answer"
        description = error.message ?: "Check internet and try Ask by voice again."
        selected = 2; render()
    }
    private fun pauseCapture() {
        ++questionGeneration; voice?.cancel(); voice = null
        questionStage = "idle"; answerPages = emptyList()
        ++generation; active = false; automatic = false
        monitor?.close(); monitor = null; gate.complete(false)
        if (switchingCamera) { switchingCamera = false; busy = false }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (camera != null) { camera?.close(); camera = null; busy = false }
        title = "Paused"; description = "Camera stopped. Select Start / Resume."; selected = 0; render()
    }
    override fun onResume() {
        super.onResume()
        MemoryWorker.onStatus = { render() }
        render()
    }
    override fun onPause() { MemoryWorker.onStatus = null; pauseCapture(); super.onPause() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        title = "Paused"
        val allowed = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        description = if (requestCode == 2) {
            if (allowed) "Microphone allowed. Select Ask by voice." else "Microphone denied. Select Ask by voice to retry."
        } else if (allowed) "Camera allowed. Select Start / Resume." else "Camera denied. Select Start / Resume to retry."
        selected = if (requestCode == 2) 2 else 0
        render()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_CENTER -> select()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
