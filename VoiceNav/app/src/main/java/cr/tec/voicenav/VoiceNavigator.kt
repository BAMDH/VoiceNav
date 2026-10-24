package cr.tec.voicenav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Screen { DASHBOARD, DEVICE, ROUTINE, CONFIRM }
enum class Device(val label: String) { LIGHTS("Lights"), FAN("Fan"), TV("TV") }

/** Tarjetas del dashboard en cuadrícula 2x2: 0 Lights, 1 Fan / 2 TV, 3 Routines. */
val DASHBOARD_CARDS = listOf("Lights", "Fan", "TV", "Routines")
val ROUTINES = listOf("Study Mode", "Night Mode", "Welcome Home")

/** Confirmacion */
sealed interface PendingAction {
    data class StartRoutine(val index: Int) : PendingAction
    data class StopRoutine(val index: Int) : PendingAction
}

data class UiState(
    val screen: Screen = Screen.DASHBOARD,
    val focus: Int = 0,                                   // foco en dashboard
    val device: Device = Device.LIGHTS,                   // dispositivo abierto
    val deviceOn: Map<Device, Boolean> = mapOf(Device.LIGHTS to true, Device.FAN to false, Device.TV to false),
    val routineFocus: Int = 0,                            // rutina seleccionada: se recuerda al salir y volver a entrar
    val routinesOn: Set<Int> = emptySet(),                // rutinas en RUNNING; el resto está en STANDBY
    val pending: PendingAction? = null,                   // acción en la pantalla Confirm
    val message: String = "Voice Ready"
)

/**
 *  - yes: entrar / confirmar
 *  - no:  salir de la pantalla /confirmar
 *  - go / stop: iniciar / detener la rutina
 *  - on / off: encender / apagar el dispositivo
 *  - up / down / left / right: mover el foco
 */
fun reduce(s: UiState, c: Command): UiState = when (s.screen) {

    Screen.DASHBOARD -> when (c) {
        Command.LEFT  -> if (s.focus % 2 == 1) s.copy(focus = s.focus - 1) else s
        Command.RIGHT -> if (s.focus % 2 == 0) s.copy(focus = s.focus + 1) else s
        Command.UP    -> if (s.focus >= 2) s.copy(focus = s.focus - 2) else s
        Command.DOWN  -> if (s.focus < 2) s.copy(focus = s.focus + 2) else s
        Command.YES   ->
            if (s.focus == 3) s.copy(screen = Screen.ROUTINE, message = "Routine Mode")
            else s.copy(screen = Screen.DEVICE, device = Device.values()[s.focus], message = "Device Control")
        else -> s                                         // no/go/stop/on/off en la raíz: nada
    }

    Screen.DEVICE -> when (c) {
        Command.ON  -> s.copy(deviceOn = s.deviceOn + (s.device to true), message = "${s.device.label} ON")
        Command.OFF -> s.copy(deviceOn = s.deviceOn + (s.device to false), message = "${s.device.label} OFF")   // sin confirmación
        Command.NO  -> s.copy(screen = Screen.DASHBOARD, message = "Voice Ready")                              // salir
        else -> s
    }

    Screen.ROUTINE -> when (c) {
        Command.UP   -> s.copy(routineFocus = (s.routineFocus - 1).coerceAtLeast(0))
        Command.DOWN -> s.copy(routineFocus = (s.routineFocus + 1).coerceAtMost(ROUTINES.lastIndex))
        // go/stop en rutinas SIEMPRE piden confirmación (yes/no), sin importar el estado actual
        Command.GO   -> s.copy(screen = Screen.CONFIRM, pending = PendingAction.StartRoutine(s.routineFocus), message = "Confirmation")
        Command.STOP -> s.copy(screen = Screen.CONFIRM, pending = PendingAction.StopRoutine(s.routineFocus), message = "Confirmation")
        Command.NO   -> s.copy(screen = Screen.DASHBOARD, message = "Voice Ready")      // salir NO detiene las rutinas
        else -> s
    }

    Screen.CONFIRM -> when (c) {
        Command.YES -> when (val p = s.pending) {
            is PendingAction.StartRoutine ->
                s.copy(screen = Screen.ROUTINE, pending = null, routinesOn = s.routinesOn + p.index, message = "Running: ${ROUTINES[p.index]}")
            is PendingAction.StopRoutine ->
                s.copy(screen = Screen.ROUTINE, pending = null, routinesOn = s.routinesOn - p.index, message = "Standby: ${ROUTINES[p.index]}")
            null -> s.copy(screen = Screen.ROUTINE)
        }
        Command.NO -> s.copy(screen = Screen.ROUTINE, pending = null, message = "Cancelled")
        else -> s
    }
}

class VoiceNavigator {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    fun onCommand(c: Command) { _state.value = reduce(_state.value, c) }
}
