package jp.voicevox.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 実機/エミュレータ上での結合テスト。
 * ネイティブライブラリの初期化・LicenseGate・実合成(E2E)を検証する。
 */
@RunWith(AndroidJUnit4::class)
class VoicevoxInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun listModels_returnsAllModelsWithCharacters() = runTest {
        val voicevox = Voicevox.create(context)
        val models = voicevox.listModels()
        assertTrue(models.isNotEmpty())
        models.forEach { model ->
            assertTrue("model ${model.id} has no characters", model.characters.isNotEmpty())
            assertTrue(model.downloadURL.startsWith("https://"))
        }
    }

    @Test
    fun synthesisWithoutDownload_throwsModelNotDownloaded() = runTest {
        val voicevox = Voicevox.create(
            context,
            modelsDir = File(context.cacheDir, "vv-empty-${System.nanoTime()}"),
        )
        try {
            voicevox.synthesis("テスト", modelId = "0")
            fail("should throw ModelNotDownloaded")
        } catch (e: VoicevoxException.ModelNotDownloaded) {
            assertEquals("0", e.modelId)
        }
    }

    @Test
    fun downloadWithoutLicense_throwsLicenseNotAccepted() = runTest {
        val voicevox = Voicevox.create(
            context,
            modelsDir = File(context.cacheDir, "vv-empty-${System.nanoTime()}"),
        )
        voicevox.gate.revoke("1")
        try {
            voicevox.downloadModel("1")
            fail("should throw LicenseNotAccepted")
        } catch (e: VoicevoxException.LicenseNotAccepted) {
            assertEquals("1", e.modelId)
        }
    }

    /** E2E: モデルをダウンロードして実際に合成する。 */
    @Test
    fun endToEndSynthesis() = runTest {
        val voicevox = Voicevox.create(context)

        voicevox.acceptLicense("0")
        voicevox.downloadModel("0")
        assertTrue(voicevox.listModels().first { it.id == "0" }.isDownloaded)

        // ずんだもん(スタイル3=ノーマル)
        val wav = voicevox.synthesis("こんにちは、ずんだもんなのだ", modelId = "0", styleId = 3)
        assertTrue("wav too short: ${wav.size}", wav.size > 44)
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
    }

    /** E2E: 並列ダウンロードと部分成否。 */
    @Test
    fun parallelDownload_partialFailure() = runTest {
        val voicevox = Voicevox.create(context, maxConcurrentDownloads = 2)
        voicevox.acceptLicense("1")
        voicevox.acceptLicense("2")
        voicevox.gate.revoke("3")

        val results = voicevox.downloadModels(listOf("1", "2", "3"))
        assertTrue(results.getValue("1").isSuccess)
        assertTrue(results.getValue("2").isSuccess)
        val failure = results.getValue("3").exceptionOrNull()
        assertTrue(failure is VoicevoxException.LicenseNotAccepted)
        assertFalse(results.getValue("3").isSuccess)
    }
}
