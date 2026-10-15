package com.spatialmemory.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.spatialmemory.recorder.databinding.ActivityRecorderBinding
import kotlinx.coroutines.launch

class RecorderActivity : BaseMirrorActivity<ActivityRecorderBinding>() {
    private var selected = 0
    private var recording: WalkRecording? = null
    private var title = "Ready"
    private var detail = "Save VGA and RGB videos on the glasses.\nNo Wi-Fi or computer needed. Maximum 10 minutes."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    when (action) {
                        is TempleAction.Click -> select()
                        is TempleAction.DoubleClick -> stop("Stopped by wearer")
                        is TempleAction.SlideForward, is TempleAction.SlideDownwards -> move(1)
                        is TempleAction.SlideBackward, is TempleAction.SlideUpwards -> move(-1)
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun move(delta: Int) {
        val count = if (recording == null) 4 else 2
        selected = (selected + delta + count) % count
        render()
    }

    private fun select() {
        val current = recording
        if (current != null) {
            if (selected == 1) stop("Stopped by wearer")
            else { selected = 1; current.mark() }
        } else if (selected in 0..2) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
                return
            }
            start(WalkRecording.Mode.values()[selected])
        }
    }

    private fun start(mode: WalkRecording.Mode) {
        val run = WalkRecording(this, mode,
            onUpdate = { status, text -> title = status; detail = text; render() },
            onFinished = { status, text ->
                recording = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                selected = 0; title = status; detail = text; render()
            })
        recording = run
        selected = 1
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        run.start()
    }

    private fun stop(reason: String) { recording?.stop(reason) }

    private fun render() {
        val labels = if (recording == null)
            listOf("Record both cameras", "Record VGA only", "Record RGB only", "Stop")
        else listOf("Mark an event", "Stop and save")
        mBindingPair.updateView {
            status.text = title
            this.detail.text = this@RecorderActivity.detail
            actions.text = labels.mapIndexed { i, label ->
                (if (i == selected) "› " else "  ") + label
            }.joinToString("\n")
        }
    }

    override fun onPause() { stop("App left foreground"); super.onPause() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        title = if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) "Ready" else "Camera permission needed"
        detail = "Select a recording mode and tap to start."
        render()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_CENTER -> select()
            KeyEvent.KEYCODE_BACK -> {
                if (recording != null) stop("Back pressed") else return super.onKeyDown(keyCode, event)
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
