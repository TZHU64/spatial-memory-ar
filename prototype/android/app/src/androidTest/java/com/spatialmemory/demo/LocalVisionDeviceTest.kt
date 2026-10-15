package com.spatialmemory.demo

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real CPU model/camera smoke tests. No RGB still, HTTP upload or VLM call. */
class LocalVisionDeviceTest {
    @Test fun embeddedModelProducesFiveNormalizedViews() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pixels = ByteArray(640*480) { i -> ((i*73+i/640*19)%256).toByte() }
        val frame = VgaFrame(ByteBuffer.wrap(pixels),640,1,0,0,640,480)
        VgaEmbedder(context).use { model ->
            val features = model.encode(frame)
            assertEquals(5, features.size)
            for (row in features) {
                assertTrue(row.isNotEmpty())
                assertEquals(1.0, row.sumOf { it.toDouble()*it }, .001)
            }
            val gate = LayeredSceneGate()
            for (time in listOf(0L,200L,400L)) assertFalse(gate.sample(features,time,true))
            assertTrue(gate.sample(features,600,true))
            gate.complete(true);gate.streamRestarted()
            for (time in listOf(800L,1000L,1200L,1400L)) assertFalse(gate.sample(features,time,true))
        }
    }

    @Test fun realVgaStreamDeliversLocalFeaturesThenCloses() {
        val main = Handler(Looper.getMainLooper())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val received = CountDownLatch(1);val closed = CountDownLatch(1)
        var failure: String? = null
        var monitor: VgaMonitor? = null
        main.post {
            monitor = VgaMonitor(context,LayeredSceneGate()).also { camera ->
                camera.start(onFrame = { features, _ -> if (features != null) received.countDown() },
                    onFailure = { message -> failure=message;received.countDown() })
            }
        }
        try {
            assertTrue("No local VGA features within 25 seconds", received.await(25,TimeUnit.SECONDS))
            assertNull(failure)
        } finally {
            main.post { monitor?.close { closed.countDown() } ?: closed.countDown() }
            assertTrue("VGA camera did not close",closed.await(10,TimeUnit.SECONDS))
            main.post { activity.finish() }
        }
    }
}
