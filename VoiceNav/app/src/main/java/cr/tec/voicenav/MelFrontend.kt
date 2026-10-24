package cr.tec.voicenav

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Parámetros leídos del modelo*/
data class PreprocConfig(
    val sampleRate: Int, val clipSamples: Int, val nFft: Int, val hop: Int,
    val nMels: Int, val nFrames: Int, val logEps: Float, val classes: List<String>,
    /** true: el clasificador NO normaliza (lo hace el .onnx (Modelo A) o el front-end (Modelo B)). */
    val meanStdInGraph: Boolean = true, val mean: Float = 0f, val std: Float = 1f,
    val power: Int = 2,                       // 2: |X|^2, 1: |X|
    /** Modelo B: imagen min-max por clip -> uint8 -> (v*scale - mean)/std -> flip vertical. null = sin imagen (Modelo A). */
    val image: ImageConfig? = null,
    val minConfidence: Float = 0.80f
) {
    data class ImageConfig(val quantize: String?, val flipVertical: Boolean, val scale: Float, val mean: Float, val std: Float)

    companion object {
        fun load(ctx: Context): PreprocConfig {
            val j = JSONObject(ctx.assets.open("preprocessing.json").bufferedReader().readText())
            val cls = j.getJSONArray("classes")
            val img = j.optJSONObject("image")?.let {
                ImageConfig(
                    if (it.isNull("quantize")) null else it.getString("quantize"),
                    it.optBoolean("flip_vertical", false),
                    it.optDouble("scale", 1.0 / 255).toFloat(), it.getDouble("mean").toFloat(), it.getDouble("std").toFloat()
                )
            }
            return PreprocConfig(
                j.getInt("sample_rate"), j.getInt("clip_samples"), j.getInt("n_fft"), j.getInt("hop"),
                j.getInt("n_mels"), j.getInt("n_frames"), j.getDouble("log_eps").toFloat(),
                List(cls.length()) { cls.getString(it) },
                j.optBoolean("mean_std_in_graph", true),
                j.optDouble("mean", 0.0).toFloat(), j.optDouble("std", 1.0).toFloat(),
                j.optInt("power", 2), img, j.optDouble("min_confidence", 0.80).toFloat()
            )
        }
    }
}

/**
 *  reflect-pad (n_fft/2) -> frames -> ventana (window.bin, ya de largo n_fft) ->
 * |DFT|^power -> banco mel -> ln(x + eps) -> [Modelo B] imagen: min-max, cuantizar a uint8, normalizar, voltear eje mel.
 * Salida: FloatArray [nMels * nFrames] fila-mayor = tensor (1,1,nMels,nFrames).
 */
class MelFrontend(private val cfg: PreprocConfig, private val melFb: FloatArray, private val window: FloatArray) {
    private val nFreq = cfg.nFft / 2 + 1
    private val cosT = FloatArray(nFreq * cfg.nFft)
    private val sinT = FloatArray(nFreq * cfg.nFft)

    init {
        require(melFb.size == cfg.nMels * nFreq) { "mel_fb.bin no coincide con n_mels x n_freq" }
        require(window.size == cfg.nFft) { "window.bin no coincide con n_fft" }
        for (k in 0 until nFreq) for (n in 0 until cfg.nFft) {
            val a = 2.0 * PI * ((k.toLong() * n) % cfg.nFft) / cfg.nFft
            cosT[k * cfg.nFft + n] = cos(a).toFloat(); sinT[k * cfg.nFft + n] = sin(a).toFloat()
        }
    }

    /** @param pcm exactamente cfg.clipSamples muestras en [-1, 1] (PCM16 / 32768f). */
    fun compute(pcm: FloatArray): FloatArray {
        require(pcm.size == cfg.clipSamples)
        val pad = cfg.nFft / 2
        val n = pcm.size
        val padded = FloatArray(n + 2 * pad) { j ->
            var i = j - pad
            if (i < 0) i = -i
            if (i >= n) i = 2 * (n - 1) - i
            pcm[i]
        }
        val out = FloatArray(cfg.nMels * cfg.nFrames)
        val frame = FloatArray(cfg.nFft)
        val power = FloatArray(nFreq)
        for (t in 0 until cfg.nFrames) {
            val start = t * cfg.hop
            for (i in 0 until cfg.nFft) frame[i] = padded[start + i] * window[i]
            for (k in 0 until nFreq) {
                var re = 0f; var im = 0f
                val base = k * cfg.nFft
                for (i in 0 until cfg.nFft) { re += frame[i] * cosT[base + i]; im += frame[i] * sinT[base + i] }
                val p = re * re + im * im
                power[k] = if (cfg.power == 1) sqrt(p) else p
            }
            for (m in 0 until cfg.nMels) {
                var acc = 0f
                val base = m * nFreq
                for (k in 0 until nFreq) acc += melFb[base + k] * power[k]
                out[m * cfg.nFrames + t] = ln(acc + cfg.logEps)
            }
        }
        return cfg.image?.let { toImage(out, it) } ?: out
    }

    private fun toImage(lm: FloatArray, img: PreprocConfig.ImageConfig): FloatArray {
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (v in lm) { if (v < mn) mn = v; if (v > mx) mx = v }
        val range = maxOf(mx - mn, 1e-8f)
        val out = FloatArray(lm.size)
        for (m in 0 until cfg.nMels) {
            val dstRow = if (img.flipVertical) cfg.nMels - 1 - m else m
            for (t in 0 until cfg.nFrames) {
                var x = (lm[m * cfg.nFrames + t] - mn) / range
                x = when (img.quantize) {
                    "round" -> floor(x * 255f + 0.5f) * img.scale
                    "floor" -> floor(x * 255f) * img.scale
                    else -> x
                }
                out[dstRow * cfg.nFrames + t] = (x - img.mean) / img.std
            }
        }
        return out
    }

    companion object {
        fun load(ctx: Context, cfg: PreprocConfig) = MelFrontend(
            cfg, readFloats(ctx, "mel_fb.bin"), readFloats(ctx, "window.bin")
        )
        private fun readFloats(ctx: Context, name: String): FloatArray {
            val bytes = ctx.assets.open(name).readBytes()
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }
    }
}
