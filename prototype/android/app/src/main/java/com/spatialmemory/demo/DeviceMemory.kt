package com.spatialmemory.demo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Authoritative device-local history. Images stay in private files/captures. */
class DeviceMemory(context: Context) : SQLiteOpenHelper(context.applicationContext, "memory.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE scenes(id TEXT PRIMARY KEY, captured_at TEXT NOT NULL,
            captured_sort INTEGER NOT NULL, time_quality TEXT NOT NULL, image TEXT NOT NULL,
            scene TEXT NOT NULL, search_text TEXT NOT NULL, model TEXT NOT NULL, view_quality TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX scene_time ON scenes(captured_sort DESC)")
        db.execSQL("""CREATE TABLE objects(source_id TEXT NOT NULL, label TEXT NOT NULL,
            appearance TEXT NOT NULL, relation TEXT NOT NULL,
            FOREIGN KEY(source_id) REFERENCES scenes(id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX object_label ON objects(label,source_id)")
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported memory version") }
    fun contains(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM scenes WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM scenes", null).use { it.moveToFirst(); it.getLong(0) }

    fun save(image: File, metadata: JSONObject, scene: JSONObject) {
        val id = metadata.getString("request_id")
        val timestamp = metadata.getString("captured_at")
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!contains(id)) {
                val values = ContentValues().apply {
                    put("id", id); put("captured_at", timestamp)
                    put("captured_sort", Instant.parse(timestamp).toEpochMilli())
                    put("time_quality", metadata.getString("time_quality")); put("image", image.absolutePath)
                    put("scene", scene.toString()); put("search_text", scene.toString().lowercase())
                    put("model", BuildConfig.MIMO_MODEL); put("view_quality", scene.getString("view_quality"))
                }
                db.insertOrThrow("scenes", null, values)
                val entities = scene.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    db.insertOrThrow("objects", null, ContentValues().apply {
                        put("source_id", id); put("label", entity.getString("label").trim().lowercase())
                        put("appearance", entity.getString("appearance")); put("relation", entity.getString("relation"))
                    })
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    data class Scene(val id: String, val capturedAt: String, val quality: String, val image: File, val description: JSONObject) {
        fun catalog(): JSONObject = JSONObject().put("id", id).put("captured_at", capturedAt)
            .put("time_quality", quality).put("scene", description)
    }
    private fun read(where: String = "", args: Array<String>? = null, limit: Int): List<Scene> =
        readableDatabase.rawQuery("SELECT id,captured_at,time_quality,image,scene FROM scenes $where ORDER BY captured_sort DESC,id DESC LIMIT $limit", args).use { cursor ->
            val result = ArrayList<Scene>()
            while (cursor.moveToNext()) result.add(Scene(cursor.getString(0), cursor.getString(1), cursor.getString(2), File(cursor.getString(3)), JSONObject(cursor.getString(4))))
            result
        }

    /** Bounded prototype retrieval: recent context plus keyword matches across durable history. */
    fun candidates(question: String): List<Scene> {
        val tokens = Regex("[a-z0-9]+|[\\p{IsHan}]{1,}").findAll(question.lowercase()).flatMap { match ->
            val word = match.value
            if (word.first().code > 127 && word.length > 1) word.windowed(2).asSequence() else sequenceOf(word)
        }.distinct().take(24).toList()
        val recent = read(limit = 20)
        val matched = if (tokens.isEmpty()) emptyList() else read(
            "WHERE " + tokens.joinToString(" OR ") { "instr(search_text,?) > 0" }, tokens.toTypedArray(), 80)
        return (recent + matched + read(limit = 100)).distinctBy { it.id }.take(100)
    }
    fun lastSeen(label: String): Scene? {
        val matches = read("WHERE id IN (SELECT source_id FROM objects WHERE label=?) AND view_quality='clear'", arrayOf(label), 2)
        val first = matches.firstOrNull() ?: return null
        if (matches.size > 1 && matches[1].capturedAt == first.capturedAt) return null
        val entities = first.description.getJSONArray("entities")
        if ((0 until entities.length()).count { entities.getJSONObject(it).getString("label").trim().equals(label, true) } != 1) return null
        return first
    }
}
