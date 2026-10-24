package cr.tec.voicenav

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VoiceViewModel(app: Application) : AndroidViewModel(app) {
    private val navigator = VoiceNavigator()
    val ui: StateFlow<UiState> = navigator.state

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _heard = MutableStateFlow("-")
    val heard: StateFlow<String> = _heard.asStateFlow()

    /** No nulo si el modelo no pudo cargarse (p. ej. faltan assets): la app sigue en modo manual. */
    private val _modelError = MutableStateFlow<String?>(null)
    val modelError: StateFlow<String?> = _modelError.asStateFlow()

    private var engine: VoiceCommandEngine? = null
    private var classifier: CommandClassifier? = null

    init {
        try {
            val cfg = PreprocConfig.load(app)
            val clf = CommandClassifier(app, cfg)
            val eng = VoiceCommandEngine(cfg, MelFrontend.load(app, cfg), clf)
            classifier = clf
            engine = eng
            viewModelScope.launch {
                eng.detections.collect { d ->
                    _heard.value = "${d.command.name.lowercase()} (${(d.confidence * 100).toInt()}%)"
                    navigator.onCommand(d.command)
                }
            }
        } catch (t: Throwable) {
            _modelError.value = "Modelo no cargado: ${t.message}"
        }
    }

    fun startListening() {
        val e = engine ?: return
        e.start(viewModelScope)
        _listening.value = true
    }

    fun stopListening() {
        engine?.stop()
        _listening.value = false
    }

    override fun onCleared() {
        engine?.stop()
        classifier?.close()
    }
}
