package com.spatialmemory.demo

import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in only. Uses a supplied synthetic utterance, never the microphone. */
class MimoDeviceSmokeTest {
    @Test fun syntheticSpeechReachesMimoDirectlyFromGlasses() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMimo") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val file = File(context.filesDir, "asr-smoke.wav")
        assertTrue("Supply a synthetic WAV first", file.isFile)
        try {
            val transcript = DirectMimo().transcribe(file)
            assertTrue("Expected synthetic question about a mug", transcript.contains("mug", ignoreCase = true))
            assertTrue("Expected location question", transcript.contains("where", ignoreCase = true))
        } finally { file.delete(); instrumentation.runOnMainSync { activity.finish() } }
    }
}
