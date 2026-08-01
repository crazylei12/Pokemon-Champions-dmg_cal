package com.crazylei12.pokemonchampionsassistant

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidTeamPreviewGoldenTest {
    @Test
    fun writesEightProductionRecognitionResults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val samples = listOf(
            "Screenshot_2026-07-14-20-57-49-14_c82859162b64fe65a671e279fb4ead1a.jpg" to "sample-0.rgba",
            "Screenshot_2026-07-14-21-02-15-41_c82859162b64fe65a671e279fb4ead1a.jpg" to "sample-1.rgba",
            "Screenshot_2026-07-14-21-06-23-14_c82859162b64fe65a671e279fb4ead1a.jpg" to "sample-2.rgba",
            "Screenshot_2026-07-14-21-32-18-94_c82859162b64fe65a671e279fb4ead1a.jpg" to "sample-3.rgba",
            "Screenshot_2026-07-14-21-34-25-80_c82859162b64fe65a671e279fb4ead1a.jpg" to "sample-4.rgba",
            "Screenshot_2026-07-14-21-36-13-37_c82859162b64fe65a671e279fb4ead1a.jpg" to "sample-5.rgba",
            "Screenshot_2026-07-14-21-38-05-81_c82859162b64fe65a671e279fb4ead1a.jpg" to "sample-6.rgba",
            "Screenshot_2026-07-14-21-39-24-70_c82859162b64fe65a671e279fb4ead1a.jpg" to "sample-7.rgba",
        )
        val engine = TeamPreviewRecognitionEngine(targetContext)
        val results = JSONArray()
        try {
            samples.forEach { (name, rawAsset) ->
                val rgba = testContext.assets.open(rawAsset).use { it.readBytes() }
                assertEquals("unexpected RGBA byte count for $name", 2772 * 1240 * 4, rgba.size)
                val bitmap = Bitmap.createBitmap(2772, 1240, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
                assertEquals(2772, bitmap.width)
                assertEquals(1240, bitmap.height)
                val latch = CountDownLatch(1)
                var recognition: Result<TeamPreviewRecognitionResult>? = null
                engine.recognize(
                    bitmap,
                    TeamPreviewCaptureTiming(System.nanoTime(), 0.0, 0.0),
                ) {
                    recognition = it
                    latch.countDown()
                }
                assertTrue("recognition timed out for $name", latch.await(60, TimeUnit.SECONDS))
                val result = requireNotNull(recognition).getOrThrow()
                assertEquals(12, result.slots.size)
                assertTrue(result.slots.all { it.candidates.size == 3 })
                results.put(JSONObject().put("sample", name).put("result", result.toJson()))
                bitmap.recycle()
            }
        } finally {
            engine.close()
        }

        val output = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "AndroidTeamPreviewGoldenResults")
            .put("productionBackend", "android_opencv_4.13.0")
            .put("results", results)
        val outputFile = File(requireNotNull(targetContext.getExternalFilesDir(null)),
            "android-team-preview-golden.json")
        outputFile.writeText(output.toString(), Charsets.UTF_8)
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "cp ${outputFile.absolutePath} /data/local/tmp/pc-android-team-preview-golden.json",
            ),
        ).use { it.readBytes() }
        println("PC_ANDROID_GOLDEN_PATH=${outputFile.absolutePath}")
    }
}
