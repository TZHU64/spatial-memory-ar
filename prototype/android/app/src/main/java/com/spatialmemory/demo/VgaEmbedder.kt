package com.spatialmemory.demo

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.security.MessageDigest
import kotlin.math.sqrt

/** CPU model, constructed/used/closed exclusively on the VGA worker. */
class VgaEmbedder(context: Context) : AutoCloseable {
    private val model: ImageEmbedder
    init {
        val asset = "mobilenet_v3_small_075_224_embedder.tflite"
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(asset).use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } ==
            "0f786f8eb8bcecd1f9c5bc50f00a5e6b9f62600edb503ad3bfff5f51035b0ea5") { "VGA model integrity check failed" }
        model = ImageEmbedder.createFromOptions(context, ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(asset).setDelegate(Delegate.CPU).build())
            .setL2Normalize(true).build())
    }
    fun encode(frame: VgaFrame): Array<FloatArray> {
        val bitmap = Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val regions = arrayOf(floatArrayOf(0f,0f,1f,1f), floatArrayOf(0f,0f,.6f,.6f),
            floatArrayOf(.4f,0f,1f,.6f), floatArrayOf(0f,.4f,.6f,1f), floatArrayOf(.4f,.4f,1f,1f))
        try {
            return regions.map { r ->
                val x = (r[0]*frame.width).toInt(); val y = (r[1]*frame.height).toInt()
                // MPImage.close owns/recycles its bitmap; never give it our shared crop source.
                val view = Bitmap.createBitmap(bitmap, x, y, (r[2]*frame.width).toInt()-x, (r[3]*frame.height).toInt()-y)
                val crop = if (view === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else view
                try {
                    val image = BitmapImageBuilder(crop).build()
                    try {
                        val values = model.embed(image).embeddingResult().embeddings().first().floatEmbedding().clone()
                        val norm = sqrt(values.sumOf { it.toDouble()*it }).coerceAtLeast(1e-9)
                        FloatArray(values.size) { (values[it]/norm).toFloat() }
                    } finally { image.close() }
                } finally { if (!crop.isRecycled) crop.recycle() }
            }.toTypedArray()
        } finally { bitmap.recycle() }
    }
    override fun close() { model.close() }
}
