package cr.tec.voicenav

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Verifica Kotlin == Python: logits de la app vs logits de PyTorch (assets/test_vectors.json). */
@RunWith(AndroidJUnit4::class)
class ParityTest {
    @Test fun kotlinMatchesPython() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cfg = PreprocConfig.load(ctx)
        val tol = JSONObject(ctx.assets.open("preprocessing.json").bufferedReader().readText()).optDouble("parity_tol", 1e-3).toFloat()
        val fe = MelFrontend.load(ctx, cfg)
        CommandClassifier(ctx, cfg).use { clf ->
            val arr = JSONArray(ctx.assets.open("test_vectors.json").bufferedReader().readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.getJSONArray("pcm")
                val pcm = FloatArray(p.length()) { p.getInt(it) / 32768f }
                val expected = o.getJSONArray("logits")
                val got = clf.logits(fe.compute(pcm))
                for (k in got.indices) assertEquals(expected.getDouble(k).toFloat(), got[k], tol)
            }
        }
    }
}
