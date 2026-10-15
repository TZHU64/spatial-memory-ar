package com.spatialmemory.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Device-local commits; receipts let interrupted interpretation resume after an app restart. */
object MemoryWorker {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "MemoryAnalysis") }
    private val main = Handler(Looper.getMainLooper())
    private const val CAPACITY = 8
    private val activeReceipts = HashSet<String>()
    private var recoveryDirectory: File? = null
    private var memory: DeviceMemory? = null
    @Volatile private var outstanding = 0
    @Volatile private var completed = 0
    @Volatile private var skipped = 0
    @Volatile private var failures = 0
    // Set and cleared on the main thread; callbacks never restart capture or replace its card.
    var onStatus: (() -> Unit)? = null
    fun hasCapacity() = outstanding < CAPACITY
    fun status() = "$outstanding pending · $completed saved · $skipped skipped · $failures failed"
    private fun notifyStatus() { main.post { onStatus?.invoke() } }

    @Synchronized
    fun enqueue(image: File, metadata: JSONObject): Boolean {
        if (!hasCapacity()) return false
        val receipt = File(image.parentFile, image.nameWithoutExtension + ".upload.json")
        return schedule(image, metadata.toString(), receipt)
    }

    @Synchronized
    fun recoverPending(context: Context) {
        if (memory == null) memory = DeviceMemory(context.applicationContext)
        recoveryDirectory = File(context.filesDir, "captures")
        fillRecovery()
    }

    /** Resume device-local receipts across sessions; never reinterpret legacy PC uploads. */
    private fun fillRecovery() {
        val directory = recoveryDirectory ?: return
        val pending = directory.listFiles { _, name -> name.endsWith(".upload.json") }
            ?.mapNotNull { receipt ->
                try {
                    val record = JSONObject(receipt.readText())
                    val state = record.optString("state")
                    val metadata = record.optJSONObject("metadata")
                    val image = File(directory, receipt.name.removeSuffix(".upload.json") + ".jpg")
                    if ((state == "queued" || state == "processing") && metadata != null
                        && metadata.optString("storage_mode") == "device" && image.isFile
                        && !activeReceipts.contains(receipt.absolutePath))
                        Triple(metadata.optLong("capture_sequence", Long.MAX_VALUE), image, receipt to metadata.toString())
                    else null
                } catch (_: Exception) { null }
            }?.sortedBy { it.first } ?: return
        for ((_, image, pair) in pending) {
            if (!hasCapacity()) break
            try { schedule(image, pair.second, pair.first) }
            catch (_: Exception) { Log.w("SpatialWorker", "Could not restore a local upload receipt") }
        }
    }

    /** Caller holds this object's monitor. Request IDs also deduplicate local DB commits. */
    private fun schedule(image: File, snapshot: String, receipt: File): Boolean {
        if (!hasCapacity() || !activeReceipts.add(receipt.absolutePath)) return false
        try {
            receipt.writeText(JSONObject().put("state", "queued")
                .put("metadata", JSONObject(snapshot)).toString())
        } catch (e: Exception) {
            activeReceipts.remove(receipt.absolutePath)
            throw e
        }
        outstanding++
        Log.i("SpatialWorker", "Queued sequence=${JSONObject(snapshot).getLong("capture_sequence")} outstanding=$outstanding")
        notifyStatus()
        executor.execute {
            try {
                receipt.writeText(JSONObject().put("state", "processing").put("metadata", JSONObject(snapshot)).toString())
                val store = checkNotNull(memory) { "Local memory has not started" }
                val metadata = JSONObject(snapshot)
                // A crash after DB commit but before receipt completion must not re-run MiMo.
                if (!store.contains(metadata.getString("request_id"))) {
                    store.save(image, metadata, DirectMimo().describe(image))
                }
                val result = JSONObject().put("outcome", "saved").put("storage_mode", "device")
                receipt.writeText(JSONObject().put("state", "complete").put("metadata", JSONObject(snapshot))
                    .put("response", result).toString())
                if (result.optString("outcome") == "skipped_duplicate") skipped++ else completed++
                Log.i("SpatialWorker", "Completed sequence=${JSONObject(snapshot).getLong("capture_sequence")} outcome=${result.optString("outcome")}")
            } catch (e: Exception) {
                failures++
                try {
                    receipt.writeText(JSONObject().put("state", "failed")
                        .put("metadata", JSONObject(snapshot)).put("message", e.message ?: "Upload failed").toString())
                } catch (_: Exception) { }
                Log.w("SpatialWorker", "Analysis failed; local capture retained")
            } finally {
                synchronized(this) {
                    outstanding--
                    activeReceipts.remove(receipt.absolutePath)
                    fillRecovery()
                }
                notifyStatus()
            }
        }
        return true
    }
}
