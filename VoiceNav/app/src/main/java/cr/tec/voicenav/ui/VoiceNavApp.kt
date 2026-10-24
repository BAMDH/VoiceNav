package cr.tec.voicenav.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cr.tec.voicenav.DASHBOARD_CARDS
import cr.tec.voicenav.Device
import cr.tec.voicenav.PendingAction
import cr.tec.voicenav.ROUTINES
import cr.tec.voicenav.Screen
import cr.tec.voicenav.UiState
import cr.tec.voicenav.VoiceViewModel

private val Bg = Color(0xFF0B1020)
private val CardBg = Color(0xFF161C33)
private val Focus = Color(0xFF3DDBB0)
private val Accent = Color(0xFF8C93FF)
private val Muted = Color(0xFF9AA3C7)
private val Chip = Color(0xFF14323A)

@Composable
fun VoiceNavApp(vm: VoiceViewModel) {
    val ui by vm.ui.collectAsState()
    val listening by vm.listening.collectAsState()
    val heard by vm.heard.collectAsState()
    val error by vm.modelError.collectAsState()
    val ctx = LocalContext.current

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startListening()
    }
    fun toggleMic() {
        when {
            listening -> vm.stopListening()
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                vm.startListening()
            else -> permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Cabecera
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(ui.message, color = Muted, fontSize = 13.sp)
                Text("Screen ${ui.screen.ordinal + 1}/4", color = Muted, fontSize = 13.sp)
            }
            Surface(shape = RoundedCornerShape(50), color = Chip) {
                Text(
                    (if (listening) "\u25CF Listening: " else "\u25CB Mic off - ") + listeningHint(ui.screen),
                    color = Color(0xFFBFF5E6), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            // Contenido de la pantalla actual
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (ui.screen) {
                    Screen.DASHBOARD -> DashboardScreen(ui)
                    Screen.DEVICE -> DeviceScreen(ui)
                    Screen.ROUTINE -> RoutineScreen(ui)
                    Screen.CONFIRM -> ConfirmScreen(ui)
                }
            }

            // Estado, errores y controles
            error?.let { Text(it, color = Color(0xFFFFB4A9), fontSize = 12.sp) }
            Text("Heard: $heard", color = Muted, fontSize = 13.sp)
            Button(onClick = { toggleMic() }, enabled = error == null, modifier = Modifier.fillMaxWidth()) {
                Text(if (listening) "Stop microphone" else "Start microphone")
            }
        }
    }
}

private fun listeningHint(s: Screen) = when (s) {
    Screen.DASHBOARD -> "up / down / left / right / yes"
    Screen.DEVICE -> "on / off / no"
    Screen.ROUTINE -> "up / down / go / stop / no"
    Screen.CONFIRM -> "yes / no"
}

@Composable
private fun DashboardScreen(s: UiState) {
    Title("Home Dashboard")
    for (row in 0..1) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (col in 0..1) {
                val i = row * 2 + col
                val (sub, badge) = when (i) {
                    0 -> "Living room and bedroom lights" to onOff(s.deviceOn[Device.LIGHTS] == true)
                    1 -> "Air circulation and comfort mode" to onOff(s.deviceOn[Device.FAN] == true)
                    2 -> "Media and ambient screen" to onOff(s.deviceOn[Device.TV] == true)
                    else -> "Study, sleep and welcome scenes" to "OPEN"
                }
                VCard(DASHBOARD_CARDS[i], sub, badge, s.focus == i, Modifier.weight(1f).height(150.dp))
            }
        }
    }
    Hint("Voice flow: use up/down/left/right to move the focus and yes to open the selected card.")
}

@Composable
private fun DeviceScreen(s: UiState) {
    val on = s.deviceOn[s.device] == true
    Title("${s.device.label}")
    VCard("Current state", "Primary device selected", onOff(on), false, Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VCard("Turn On", "Activate with command on.", null, on, Modifier.weight(1f).height(100.dp))
        VCard("Turn Off", "Deactivate with command off.", null, !on, Modifier.weight(1f).height(100.dp))
    }
    VCard("Back to dashboard", "Leave this screen using the command no.", "NO", false, Modifier.fillMaxWidth())
    Hint("Voice flow: on/off control the selected device; no returns to the dashboard.")
}

@Composable
private fun RoutineScreen(s: UiState) {
    val desc = listOf("Desk light on, fan low, notifications muted.", "Warm lights, TV off, reduced brightness.", "Lights on and ambient music prepared.")
    Title("Routines")
    ROUTINES.forEachIndexed { i, name ->
        VCard(name, desc[i], if (i in s.routinesOn) "RUNNING" else "STANDBY", s.routineFocus == i, Modifier.fillMaxWidth())
    }
    Hint("Voice flow: move with up/down; go starts and stop stops the selected routine (you will be asked to confirm); no leaves the screen.")
}

@Composable
private fun ConfirmScreen(s: UiState) {
    Title("Confirm action")
    val question = when (val p = s.pending) {
        is PendingAction.StartRoutine -> "Start ${ROUTINES[p.index]}?"
        is PendingAction.StopRoutine -> "Stop ${ROUTINES[p.index]}?"
        null -> "Confirm action?"
    }
    VCard(question, "Say yes to execute the action or no to cancel and return.", null, false, Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VCard("yes", "Execute the action.", null, true, Modifier.weight(1f).height(100.dp))
        VCard("no", "Cancel and go back.", null, false, Modifier.weight(1f).height(100.dp))
    }
    Hint("Voice flow: yes executes the pending action, no cancels it.")
}

@Composable private fun Title(t: String) = Text(t, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Hint(t: String) = Surface(shape = RoundedCornerShape(14.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
    Text(t, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
}

private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

@Composable
private fun VCard(title: String, subtitle: String?, badge: String?, focused: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape, color = CardBg,
        modifier = modifier.border(if (focused) 2.dp else 1.dp, if (focused) Focus else Color(0x22FFFFFF), shape)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (badge != null) {
                val c = when (badge) { "ON", "ACTIVE", "RUNNING" -> Color(0xFF1F5F4E); "OFF" -> Color(0xFF5A2A33); else -> Color(0xFF2A2F5E) }
                Surface(shape = RoundedCornerShape(50), color = c, modifier = Modifier.padding(top = 8.dp)) {
                    Text(badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                }
            }
        }
    }
}
