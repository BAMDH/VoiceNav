package cr.tec.voicenav

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.exp

data class Prediction(val label: String, val confidence: Float, val probs: FloatArray)

/** Inferencia local con ONNX Runtime Mobile. Entrada "mel" (1,1,nMels,nFrames), salida "logits". */
class CommandClassifier(ctx: Context, private val cfg: PreprocConfig, modelAsset: String = "command_model.onnx") : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        ctx.assets.open(modelAsset).readBytes(),
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    )
    private val shape = longArrayOf(1, 1, cfg.nMels.toLong(), cfg.nFrames.toLong())
    private val inputName: String = session.inputNames.first()      // "mel" (Modelo A) o "input" (Modelo B)

    init {
        require(session.outputInfo.size == 1) { "Se esperaba una sola salida (logits)" }
        val nOut = (session.outputInfo.values.first().info as ai.onnxruntime.TensorInfo).shape.last().toInt()
        require(nOut == cfg.classes.size) { "El modelo tiene $nOut salidas pero preprocessing.json define ${cfg.classes.size} clases" }
    }

    fun logits(mel: FloatArray): FloatArray {
        val x = if (cfg.meanStdInGraph) mel else FloatArray(mel.size) { (mel[it] - cfg.mean) / cfg.std }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(x), shape).use { input ->
            session.run(mapOf(inputName to input)).use { res ->
                @Suppress("UNCHECKED_CAST")
                (res[0].value as Array<FloatArray>)[0]
            }
        }
    }

    fun classify(mel: FloatArray): Prediction {
        val lg = logits(mel)
        val mx = lg.max()
        val e = FloatArray(lg.size) { exp(lg[it] - mx) }
        val s = e.sum()
        val p = FloatArray(e.size) { e[it] / s }
        val i = p.indices.maxByOrNull { p[it] }!!
        return Prediction(cfg.classes[i], p[i], p)
    }

    override fun close() { session.close() }
}
