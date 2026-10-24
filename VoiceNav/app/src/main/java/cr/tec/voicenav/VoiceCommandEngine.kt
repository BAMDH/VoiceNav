package cr.tec.voicenav

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

enum class Command {
    YES, NO, UP, DOWN, LEFT, RIGHT, ON, OFF, STOP, GO;
    companion object {
        fun fromLabel(s: String): Command? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}

data class Detection(val command: Command, val confidence: Float)

/**
 * Captura continua (16 kHz mono PCM16), ventana deslizante de 1 s y una inferencia cada [hopMs].
 * Permiso requerido: RECORD_AUDIO (pedirlo antes de llamar a start()).
 */
class VoiceCommandEngine(
    private val cfg: PreprocConfig,
    private val frontend: MelFrontend,
    private val classifier: CommandClassifier,
    private val hopMs: Int = 250,
    private val minConfidence: Float = cfg.minConfidence,
    private val rmsGate: Float = 0.01f,
    private val cooldownMs: Long = 900
) {
    private val _detections = MutableSharedFlow<Detection>(extraBufferCapacity = 8)
    val detections: SharedFlow<Detection> = _detections
    /** Última predicción cruda (para mostrar "Listening..." / depurar en la UI). */
    @Volatile var lastPrediction: Prediction? = null; private set

    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            val hopSamples = cfg.sampleRate * hopMs / 1000
            val minBuf = AudioRecord.getMinBufferSize(cfg.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, cfg.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, hopSamples * 4)
            )
            val ring = FloatArray(cfg.clipSamples)          // última 1 s, la más reciente al final
            val chunk = ShortArray(hopSamples)
            var lastEmit = 0L
            rec.startRecording()
            try {
                while (isActive) {
                    var read = 0
                    while (read < hopSamples && isActive) {
                        val r = rec.read(chunk, read, hopSamples - read)
                        if (r <= 0) break
                        read += r
                    }
                    if (read < hopSamples) continue
                    System.arraycopy(ring, hopSamples, ring, 0, ring.size - hopSamples)
                    for (i in 0 until hopSamples) ring[ring.size - hopSamples + i] = chunk[i] / 32768f

                    if (rms(ring, ring.size - cfg.sampleRate / 2) < rmsGate) continue     // silencio: no inferir
                    val pred = classifier.classify(frontend.compute(ring))
                    lastPrediction = pred
                    val cmd = Command.fromLabel(pred.label) ?: continue                   // ignora silence/unknown
                    val now = System.currentTimeMillis()
                    if (pred.confidence >= minConfidence && now - lastEmit >= cooldownMs) {
                        lastEmit = now
                        _detections.tryEmit(Detection(cmd, pred.confidence))
                    }
                }
            } finally { rec.stop(); rec.release() }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private fun rms(x: FloatArray, from: Int): Float {
        var s = 0f
        for (i in from until x.size) s += x[i] * x[i]
        return sqrt(s / (x.size - from))
    }
}
