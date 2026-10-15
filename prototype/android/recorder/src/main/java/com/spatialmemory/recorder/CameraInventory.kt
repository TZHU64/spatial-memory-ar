package com.spatialmemory.recorder

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Range
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

data class CameraSpec(
    val role: String, val id: String, val size: Size, val orientation: Int,
    val timestampSource: Int, val fpsRange: Range<Int>?, val encoderName: String
) {
    fun json() = JSONObject().put("role", role).put("camera_id", id)
        .put("width", size.width).put("height", size.height).put("target_fps", 15)
        .put("orientation_degrees", orientation).put("timestamp_source", timestampSource)
        .put("timestamp_source_name", if (timestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
            "REALTIME" else "UNKNOWN")
        .put("ae_fps_range", fpsRange?.let { JSONArray(listOf(it.lower, it.upper)) } ?: JSONObject.NULL)
        .put("encoder", encoderName).put("video", "$role.mp4")
        .put("frames", "${role}_frames.jsonl").put("captures", "${role}_captures.jsonl")
}

class CameraInventory(private val manager: CameraManager) {
    private val cameras = manager.cameraIdList.associateWith { manager.getCameraCharacteristics(it) }
    val concurrentSets: Set<Set<String>> = manager.concurrentCameraIds

    fun specs(mode: WalkRecording.Mode): List<CameraSpec> {
        val roles = when (mode) {
            WalkRecording.Mode.DUAL -> listOf("vga", "rgb")
            WalkRecording.Mode.VGA -> listOf("vga")
            WalkRecording.Mode.RGB -> listOf("rgb")
        }
        return roles.map { role ->
            val candidates = cameras.filter { (_, c) ->
                val mono = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ==
                    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO
                mono == (role == "vga")
            }
            val entry = if (role == "vga") candidates.entries.singleOrNull()
                else candidates.entries.maxByOrNull { it.value.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    ?.let { s -> s.width.toLong() * s.height } ?: 0L }
            requireNotNull(entry) { "No unambiguous $role camera is available." }
            val c = entry.value
            val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            val maxW = if (role == "vga") 640 else 1280
            val maxH = if (role == "vga") 480 else 720
            val choices = sizes.filter { it.width <= maxW && it.height <= maxH && it.width % 2 == 0 && it.height % 2 == 0 }
                .distinct().sortedByDescending { it.width * it.height }
            val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { mime -> mime.equals("video/avc", true) } }
                .sortedByDescending { it.isHardwareAccelerated }
            val combination = choices.asSequence().flatMap { size ->
                encoders.asSequence().filter { codec ->
                    val cap = codec.getCapabilitiesForType("video/avc")
                    cap.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) &&
                        cap.videoCapabilities.areSizeAndRateSupported(size.width, size.height, 15.0)
                }.map { size to it.name }
            }.firstOrNull() ?: error("No supported $role YUV / H.264 encoder combination.")
            val range = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.lower <= 15 && it.upper >= 15 }?.minByOrNull { it.upper - it.lower }
            CameraSpec(role, entry.key, combination.first,
                c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ?: 0, range, combination.second)
        }
    }

    fun json() = JSONObject().put("advertised_concurrent_sets", JSONArray(concurrentSets.map { JSONArray(it.toList()) }))
        .put("cameras", JSONArray(cameras.map { (id, c) ->
            JSONObject().put("id", id)
                .put("filter_arrangement", c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT))
                .put("timestamp_source", c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
                .put("yuv_sizes", JSONArray(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.YUV_420_888)?.map { it.toString() }.orEmpty()))
        }))
}
