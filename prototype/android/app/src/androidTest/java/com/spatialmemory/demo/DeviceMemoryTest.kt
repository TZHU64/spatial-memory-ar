package com.spatialmemory.demo

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated synthetic memories: no wearer images and no online calls. */
class DeviceMemoryTest {
    private fun scene(quality: String = "clear", count: Int = 1): JSONObject = JSONObject()
        .put("summary", "Blue mug beside laptop").put("place", "office desk")
        .put("objects", JSONArray().put("blue mug")).put("activities", JSONArray())
        .put("visible_text", JSONArray()).put("uncertainties", JSONArray()).put("view_quality", quality)
        .put("entities", JSONArray().apply { repeat(count) { put(JSONObject().put("label", "mug")
            .put("appearance", "blue").put("relation", "beside laptop")) } })

    @Test fun durableHistoryRejectsDuplicatesAndOrdersByCaptureTime() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${UUID.randomUUID()}.db"
        val context = object : ContextWrapper(target) {
            override fun getDatabasePath(ignored: String): File = target.getDatabasePath(name)
            override fun openOrCreateDatabase(ignored: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?): android.database.sqlite.SQLiteDatabase =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(ignored), factory)
            override fun openOrCreateDatabase(ignored: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, handler: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(ignored).path, factory, handler)
            override fun getApplicationContext(): Context = this
        }
        val image = File(target.cacheDir, "$name.jpg").apply { writeBytes(byteArrayOf(1)) }
        fun metadata(id: String, time: String) = JSONObject().put("request_id", id).put("captured_at", time).put("time_quality", "approximate")
        try {
            DeviceMemory(context).use { memory ->
                memory.save(image, metadata("new", "2026-10-15T11:00:00Z"), DirectMimo.validateScene(scene()))
                memory.save(image, metadata("old", "2026-10-15T09:00:00Z"), scene())
                memory.save(image, metadata("new", "2026-10-15T11:00:00Z"), scene())
                assertEquals(2L, memory.count())
                assertEquals("new", memory.lastSeen("mug")?.id)
            }
            DeviceMemory(context).use { memory ->
                assertEquals(2L, memory.count())
                assertEquals("new", memory.lastSeen("mug")?.id)
                memory.save(image, metadata("ambiguous", "2026-10-15T12:00:00Z"), scene(count = 2))
                assertNull(memory.lastSeen("mug"))
            }
        } finally { target.deleteDatabase(name); image.delete() }
    }

    @Test fun malformedScenesAndInventedCitationsAreRejected() {
        try { DirectMimo.validateScene(scene().put("view_quality", "certain")); fail("Accepted bad quality") } catch (_: IllegalArgumentException) { }
        val answer = JSONObject().put("answer", "On the desk").put("evidence_ids", JSONArray().put("invented")).put("insufficient_evidence", false)
        try { DirectMimo.validateAnswer(answer, setOf("real")); fail("Accepted invented source") } catch (_: IllegalArgumentException) { }
        val uncited = DirectMimo.validateAnswer(answer.put("evidence_ids", JSONArray()), setOf("real"))
        assertTrue(uncited.getBoolean("insufficient_evidence"))
    }
}
