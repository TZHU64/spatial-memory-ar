package com.spatialmemory.recorder

import android.app.Application
import com.ffalcon.mercury.android.sdk.MercurySDK
import android.os.Environment
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

class RecorderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MercurySDK.init(this)
        // This only runs at process creation. A previous run cannot still own the cameras.
        val root = getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let { File(it, "WalkRecorder") }
        root?.listFiles()?.filter { it.isDirectory }?.forEach { directory ->
            val file = File(directory, "manifest.json")
            if (file.isFile) try {
                val record = JSONObject(file.readText())
                if (record.optString("state") in listOf("starting", "recording", "stopping")) {
                    record.put("state", "incomplete").put("error", "App process ended before recording was finalized.")
                    val atomic = AtomicFile(file)
                    val output = atomic.startWrite()
                    try { output.write(record.toString(2).toByteArray()); atomic.finishWrite(output) }
                    catch (e: Exception) { atomic.failWrite(output); throw e }
                }
            } catch (_: Exception) { /* Keep unreadable evidence intact for manual recovery. */ }
        }
    }
}
