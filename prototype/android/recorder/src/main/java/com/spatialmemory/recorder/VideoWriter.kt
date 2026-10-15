package com.spatialmemory.recorder

import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.TreeMap

/** One bounded encoder, used only on its camera's worker thread. */
class VideoWriter(private val folder: File, private val spec: CameraSpec, private val epochNs: Long) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var frames: java.io.BufferedWriter? = null
    private var track = -1
    private var muxerStarted = false
    private val pending = TreeMap<Long, JSONObject>()
    private var firstSensorNs = 0L
    private var firstCallbackNs = 0L
    private var lastSensorNs = 0L
    private var lastSubmittedPts = -1L
    private var eos = false
    private var closed = false
    var acquired = 0L; private set
    var submitted = 0L; private set
    @Volatile var encoded = 0L; private set
    @Volatile var lastOutputElapsedNs = 0L; private set
    private var rateSkipped = 0L
    private var busyDropped = 0L
    private var timestampDropped = 0L
    private var lastOutputPts = -1L
    private var firstEncodedSessionNs: Long? = null
    private var lastEncodedSessionNs: Long? = null
    private var verifiedSamples = 0L
    private var maxMuxerAdjustmentUs = 0L

    fun prepare() {
        frames = File(folder, "${spec.role}_frames.jsonl").bufferedWriter()
        val encoder = MediaCodec.createByCodecName(spec.encoderName)
        codec = encoder
        val format = MediaFormat.createVideoFormat("video/avc", spec.size.width, spec.size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, if (spec.role == "vga") 1_500_000 else 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        muxer = MediaMuxer(File(folder, "${spec.role}.mp4").path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
            setOrientationHint(spec.orientation)
        }
        encoder.start()
    }

    fun write(image: Image, callbackNs: Long) {
        acquired++
        val sensorNs = image.timestamp
        if (sensorNs <= 0 || sensorNs <= lastSensorNs) { timestampDropped++; return }
        lastSensorNs = sensorNs
        if (firstSensorNs == 0L) { firstSensorNs = sensorNs; firstCallbackNs = callbackNs }
        val pts = (sensorNs - firstSensorNs) / 1000
        // Allow small sensor scheduling jitter at 15 fps. Never invent or duplicate frames.
        if (lastSubmittedPts >= 0 && pts - lastSubmittedPts < 60_000) { rateSkipped++; return }
        drain(0)
        check(pending.size < 120) { "Encoder stopped producing frames." }
        val encoder = checkNotNull(codec)
        val index = encoder.dequeueInputBuffer(0)
        if (index < 0) { busyDropped++; return }
        val target = checkNotNull(encoder.getInputImage(index)) { "Encoder does not provide writable YUV images." }
        target.use {
            val crop = image.cropRect
            val out = it.cropRect
            require(crop.width() == spec.size.width && crop.height() == spec.size.height) { "Unexpected camera crop." }
            for (p in 0..2) {
                val divisor = if (p == 0) 1 else 2
                val dst = it.planes[p]
                if (p > 0 && spec.role == "vga") {
                    PlaneCopy.neutral(dst.buffer, dst.rowStride, dst.pixelStride, out.left / 2, out.top / 2,
                        spec.size.width / 2, spec.size.height / 2)
                } else {
                    val src = image.planes[p]
                    PlaneCopy.copy(src.buffer, src.rowStride, src.pixelStride, crop.left / divisor, crop.top / divisor,
                        dst.buffer, dst.rowStride, dst.pixelStride, out.left / divisor, out.top / divisor,
                        spec.size.width / divisor, spec.size.height / divisor)
                }
            }
        }
        encoder.queueInputBuffer(index, 0, spec.size.width * spec.size.height * 3 / 2, pts, 0)
        val realClock = spec.timestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val mappedNs = if (realClock) sensorNs else firstCallbackNs + sensorNs - firstSensorNs
        pending[pts] = JSONObject().put("input_index", submitted).put("encoder_pts_us", pts)
            .put("sensor_timestamp_ns", sensorNs.toString()).put("callback_elapsed_ns", callbackNs.toString())
            .put("session_elapsed_ns", (mappedNs - epochNs).toString())
            .put("clock_mapping", if (realClock) "sensor_realtime" else "first_callback_anchor_approximate")
        lastSubmittedPts = pts
        submitted++
        drain(0)
    }

    private fun drain(timeoutUs: Long) {
        val encoder = codec ?: return
        val info = MediaCodec.BufferInfo()
        // Bounded even if a faulty codec never returns INFO_TRY_AGAIN_LATER.
        repeat(128) {
            val index = encoder.dequeueOutputBuffer(info, timeoutUs)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(!muxerStarted) { "Unexpected encoder format change." }
                track = checkNotNull(muxer).addTrack(encoder.outputFormat)
                checkNotNull(muxer).start()
                muxerStarted = true
            } else if (index >= 0) {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(muxerStarted)
                        check(info.presentationTimeUs > lastOutputPts) { "Nonmonotonic encoded timestamps." }
                        val metadata = checkNotNull(pending.remove(info.presentationTimeUs)) { "Unmatched encoded frame." }
                        val buffer = checkNotNull(encoder.getOutputBuffer(index))
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        checkNotNull(muxer).writeSampleData(track, buffer, info)
                        val sessionNs = metadata.getString("session_elapsed_ns").toLong()
                        if (firstEncodedSessionNs == null) firstEncodedSessionNs = sessionNs
                        lastEncodedSessionNs = sessionNs
                        metadata.put("video_frame_index", encoded).put("key_frame", info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        frames?.apply { write(metadata.toString()); newLine(); if (encoded % 15 == 0L) flush() }
                        encoded++
                        lastOutputPts = info.presentationTimeUs
                        lastOutputElapsedNs = SystemClock.elapsedRealtimeNanos()
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                } finally { encoder.releaseOutputBuffer(index, false) }
                if (eos) return
            }
        }
    }

    fun finish(): JSONObject {
        if (closed) return stats(false)
        closed = true
        var error: String? = null
        try {
            codec?.let { encoder ->
                val deadline = SystemClock.elapsedRealtime() + 3000
                var sent = false
                while (!sent && SystemClock.elapsedRealtime() < deadline) {
                    drain(1000)
                    val input = encoder.dequeueInputBuffer(1000)
                    if (input >= 0) {
                        encoder.queueInputBuffer(input, 0, 0, (lastSubmittedPts + 1).coerceAtLeast(0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sent = true
                    }
                }
                while (sent && !eos && SystemClock.elapsedRealtime() < deadline) drain(1000)
                check(eos) { "Encoder finalization timed out." }
                check(pending.isEmpty()) { "Encoder did not emit all submitted frames." }
            }
        } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
        try { codec?.stop() } catch (e: Exception) { if (error == null) error = e.message }
        try { codec?.release() } catch (_: Exception) { }
        codec = null
        try { if (muxerStarted) muxer?.stop() } catch (e: Exception) { if (error == null) error = e.message }
        try { muxer?.release() } catch (_: Exception) { }
        muxer = null
        try { frames?.close() } catch (e: Exception) { if (error == null) error = e.message }
        if (error == null && encoded > 0) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(File(folder, "${spec.role}.mp4").path)
                check(extractor.trackCount == 1) { "Unexpected MP4 track count." }
                extractor.selectTrack(0)
                var count = 0L
                var previous = -1L
                val frameFile = File(folder, "${spec.role}_frames.jsonl")
                val atomic = android.util.AtomicFile(frameFile)
                frameFile.bufferedReader().use { input ->
                    val stream = atomic.startWrite()
                    try {
                        val output = stream.bufferedWriter()
                        input.forEachLine { line ->
                            val timeUs = extractor.sampleTime
                            check(timeUs > previous) { "MP4 frame times are not increasing or a sample is missing." }
                            val row = JSONObject(line)
                            check(row.getLong("video_frame_index") == count)
                            maxMuxerAdjustmentUs = maxOf(maxMuxerAdjustmentUs, kotlin.math.abs(timeUs - row.getLong("encoder_pts_us")))
                            row.put("mp4_pts_us", timeUs)
                            output.write(row.toString()); output.newLine()
                            previous = timeUs
                            count++
                            extractor.advance()
                        }
                        check(extractor.sampleTime < 0) { "MP4 contains unlogged samples." }
                        check(count == encoded) { "MP4 frame count differs from the frame log." }
                        output.flush()
                        atomic.finishWrite(stream)
                    } catch (e: Exception) { atomic.failWrite(stream); throw e }
                }
                verifiedSamples = count
            } catch (e: Exception) { error = "MP4 verification: ${e.message}" }
            finally { extractor.release() }
        }
        val complete = error == null && encoded > 0 && eos
        return stats(complete).put("error", error ?: JSONObject.NULL)
    }

    private fun stats(complete: Boolean) = JSONObject().put("complete", complete)
        .put("acquired_images", acquired).put("submitted_frames", submitted).put("encoded_frames", encoded)
        .put("rate_skipped", rateSkipped).put("encoder_busy_dropped", busyDropped)
        .put("nonmonotonic_timestamp_dropped", timestampDropped).put("unemitted_frames", pending.size)
        .put("first_sensor_timestamp_ns", firstSensorNs.toString()).put("first_callback_elapsed_ns", firstCallbackNs.toString())
        .put("last_sensor_timestamp_ns", lastSensorNs.toString()).put("last_encoder_pts_us", lastOutputPts)
        .put("first_encoded_session_ns", firstEncodedSessionNs?.toString() ?: JSONObject.NULL)
        .put("last_encoded_session_ns", lastEncodedSessionNs?.toString() ?: JSONObject.NULL)
        .put("verified_mp4_samples", verifiedSamples)
        .put("max_muxer_timestamp_adjustment_us", maxMuxerAdjustmentUs)
        .put("video_bytes", File(folder, "${spec.role}.mp4").length())
}
