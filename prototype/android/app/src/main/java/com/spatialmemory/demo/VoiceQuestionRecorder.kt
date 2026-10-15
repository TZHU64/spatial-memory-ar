package com.spatialmemory.demo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Explicit tap-to-record; PCM WAV matches MiMo ASR's supported input formats. */
class VoiceQuestionRecorder(private val context: Context) {
    @Volatile private var finish = false
    @Volatile private var cancelled = false
    private val main = Handler(Looper.getMainLooper())
    fun stop() { finish = true }
    fun cancel() { cancelled = true; finish = true }

    @SuppressLint("MissingPermission")
    fun start(callback: (Result<File>) -> Unit) {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        Thread({
            var recorder: AudioRecord? = null
            val result = runCatching {
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Microphone does not support 16 kHz recording" }
                val source = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
                recorder = source
                check(source.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not initialize" }
                val pcm = ByteArrayOutputStream()
                val buffer = ShortArray(1600)
                source.startRecording()
                check(source.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start" }
                while (!finish && pcm.size() < 16000 * 2 * 15) {
                    val count = source.read(buffer, 0, buffer.size)
                    check(count > 0) { "Microphone recording failed" }
                    for (i in 0 until count) { pcm.write(buffer[i].toInt() and 255); pcm.write((buffer[i].toInt() shr 8) and 255) }
                }
                check(!cancelled) { "Recording cancelled" }
                check(pcm.size() >= 16000) { "Recording too short. Please speak for at least half a second." }
                val bytes = pcm.toByteArray()
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".toByteArray()).putInt(36 + bytes.size).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000)
                    .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes.size).array()
                val directory = File(context.cacheDir, "voice").apply { mkdirs() }
                File(directory, "${UUID.randomUUID()}.wav").also { file ->
                    file.outputStream().use { it.write(header); it.write(bytes) }
                }
            }
            try { recorder?.stop() } catch (_: Exception) { }
            recorder?.release()
            main.post {
                if (!cancelled) callback(result) else result.getOrNull()?.delete()
            }
        }, "VoiceQuestion").start()
    }
}
