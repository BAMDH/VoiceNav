package cr.tec.voicenav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    private fun run(vararg cs: Command) = cs.fold(UiState()) { s, c -> reduce(s, c) }
    private val toRoutines = arrayOf(Command.RIGHT, Command.DOWN, Command.YES)     // dashboard -> Routines

    @Test fun focusMovesInGrid() = assertEquals(3, run(Command.RIGHT, Command.DOWN).focus)

    @Test fun yesEntersAndNoLeaves() {
        assertEquals(Screen.DEVICE, run(Command.YES).screen)
        assertEquals(Screen.DASHBOARD, run(Command.YES, Command.NO).screen)
        assertEquals(Screen.ROUTINE, run(*toRoutines).screen)
        assertEquals(Screen.DASHBOARD, run(*toRoutines, Command.NO).screen)
    }

    @Test fun goAndStopNoLongerEnterOrLeaveScreens() {
        assertEquals(UiState(), run(Command.GO))                                   // dashboard: nada
        assertEquals(UiState(), run(Command.STOP))
        assertEquals(Screen.DEVICE, run(Command.YES, Command.STOP, Command.GO).screen)   // device: nada
    }

    @Test fun noInDashboardDoesNothing() = assertEquals(UiState(), run(Command.NO))

    @Test fun deviceOnOffHaveNoConfirmation() {
        val off = run(Command.YES, Command.OFF)                                    // Lights
        assertEquals(Screen.DEVICE, off.screen); assertEquals(false, off.deviceOn[Device.LIGHTS]); assertNull(off.pending)
        assertEquals(true, reduce(off, Command.ON).deviceOn[Device.LIGHTS])
    }

    @Test fun onOffDoNothingInRoutines() {
        val s = run(*toRoutines)
        assertEquals(s, reduce(s, Command.ON)); assertEquals(s, reduce(s, Command.OFF))
    }

    @Test fun goAsksToStartRoutineAndYesNoAnswer() {
        val asked = run(*toRoutines, Command.GO)
        assertEquals(Screen.CONFIRM, asked.screen); assertEquals(PendingAction.StartRoutine(0), asked.pending)
        assertTrue(asked.routinesOn.isEmpty())
        val yes = reduce(asked, Command.YES)
        assertEquals(Screen.ROUTINE, yes.screen); assertEquals(setOf(0), yes.routinesOn)
        val no = reduce(asked, Command.NO)
        assertEquals(Screen.ROUTINE, no.screen); assertTrue(no.routinesOn.isEmpty())
    }

    @Test fun goAndStopOnlyAnswerConfirmationNotGoStop() {
        val asked = run(*toRoutines, Command.GO)
        assertEquals(asked, reduce(asked, Command.GO)); assertEquals(asked, reduce(asked, Command.STOP))
    }

    @Test fun stopAsksToStopRoutineEvenInStandby() {
        val s = run(*toRoutines, Command.STOP)
        assertEquals(Screen.CONFIRM, s.screen); assertEquals(PendingAction.StopRoutine(0), s.pending)
    }

    @Test fun goOnRunningRoutineStillAsksAndDoesNotBreak() {
        val s = run(*toRoutines, Command.GO, Command.YES, Command.GO)
        assertEquals(Screen.CONFIRM, s.screen)
        assertEquals(setOf(0), reduce(s, Command.YES).routinesOn)
        assertEquals(setOf(0), reduce(s, Command.NO).routinesOn)
    }

    @Test fun routineStopConfirmedBringsStandby() {
        var s = run(*toRoutines, Command.GO, Command.YES)
        s = reduce(reduce(s, Command.STOP), Command.YES)
        assertTrue(s.routinesOn.isEmpty())
    }

    @Test fun selectionIsRememberedAndRoutinesKeepRunningAfterLeaving() {
        var s = run(*toRoutines, Command.DOWN, Command.DOWN, Command.GO, Command.YES)   // Welcome Home -> RUNNING
        s = reduce(s, Command.NO)                                                      // sale al dashboard
        assertEquals(Screen.DASHBOARD, s.screen)
        s = reduce(s, Command.YES)                                                     // el foco sigue en Routines -> entra
        assertEquals(Screen.ROUTINE, s.screen)
        assertEquals(2, s.routineFocus); assertEquals(setOf(2), s.routinesOn)
    }

    @Test fun routinesAreIndependent() {
        var s = run(*toRoutines, Command.GO, Command.YES, Command.DOWN, Command.GO, Command.YES)
        assertEquals(setOf(0, 1), s.routinesOn)
        s = reduce(reduce(s, Command.STOP), Command.YES)                               // detiene solo la seleccionada (1)
        assertEquals(setOf(0), s.routinesOn)
    }
}
