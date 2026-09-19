package cn.zgccrui.backscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** CLI-contract regression tests only; no UI unit tests. */
class DisplayControllerTest {
    private class Phone : CommandRunner {
        var enabled = false
        var power = "OFF"
        var known = true
        var targetedWake = true
        var wakes = true
        var rejected: CommandResult? = null
        val calls = mutableListOf<List<String>>()

        override fun run(arguments: List<String>): CommandResult {
            calls += arguments
            val command = arguments.drop(1)
            return when {
                arguments.first() == "/system/bin/dumpsys" -> CommandResult(0, if (known) {
                    "  Display 0:\n    mIsEnabled=true\n  Display 1:\n    mDisplayId=1\n    mIsEnabled=$enabled\n  Display Groups:\n"
                } else "  Display 0:\n    mIsEnabled=true\n")
                command == listOf("display", "get-displays") -> CommandResult(0,
                    "Displays:\nDisplay id 0: DisplayInfo{\"内置屏幕\", state ON, committedState ON}\n" +
                        if (enabled && known) "Display id 1: DisplayInfo{\"背屏\", state $power, committedState $power}\n" else "",
                )
                command == listOf("display", "enable-display", "1") -> rejected ?: run {
                    enabled = true
                    CommandResult(0, "")
                }
                command == listOf("display", "disable-display", "1") -> rejected ?: run {
                    enabled = false
                    power = "OFF"
                    CommandResult(0, "")
                }
                command == listOf("power", "help") -> CommandResult(255,
                    "Power manager (power) commands:\n  sleep\n      --display-id <ids>\n  wakeup (<delay>)\n" +
                        if (targetedWake) "      --display-id <ids>: Only wake listed displays.\n" else "    Wake default groups.\n",
                )
                command == listOf("power", "wakeup", "--display-id", "1") -> {
                    if (wakes) power = "ON"
                    CommandResult(0, "")
                }
                else -> error("Unexpected command: $arguments")
            }
        }
    }

    @Test fun enablingAlsoWakesTheBackDisplayAfterHelpReturns255() {
        val phone = Phone()
        val result = DisplayController(phone) {}.setEnabled(1, true)
        assertTrue(result.snapshot.enabled)
        assertEquals("ON", result.snapshot.power)
        assertFalse(result.warning)
        val enable = phone.calls.indexOf(listOf("/system/bin/cmd", "display", "enable-display", "1"))
        val wake = phone.calls.indexOf(listOf("/system/bin/cmd", "power", "wakeup", "--display-id", "1"))
        assertTrue(enable >= 0 && wake > enable)
    }

    @Test fun disabledDisplayCanBeRecoveredEvenWhenMissingFromEnumeration() {
        val phone = Phone()
        assertFalse(DisplayController(phone) {}.query(1).enabled)
        assertTrue(DisplayController(phone) {}.setEnabled(1, true).snapshot.enabled)
    }

    @Test fun disablingRemovesTheLogicalDisplayWithoutWakingAnything() {
        val phone = Phone().apply { enabled = true; power = "ON" }
        val result = DisplayController(phone) {}.setEnabled(1, false)
        assertFalse(result.snapshot.enabled)
        assertTrue(phone.calls.none { "wakeup" in it })
    }

    @Test fun sleepDisplayIdOptionDoesNotAuthorizeAnUntargetedWakeup() {
        val phone = Phone().apply { targetedWake = false }
        val result = DisplayController(phone) {}.setEnabled(1, true)
        assertTrue(result.snapshot.enabled)
        assertTrue(result.warning)
        assertTrue(phone.calls.none { "wakeup" in it })
    }

    @Test fun wakeupNoOpMustNotReportAnAwakeScreen() {
        val phone = Phone().apply { wakes = false }
        val result = DisplayController(phone) {}.setEnabled(1, true)
        assertTrue(result.warning)
        assertEquals("OFF", result.snapshot.power)
    }

    @Test fun nonexistentAndMainDisplayCannotBeModified() {
        val phone = Phone().apply { known = false }
        val controller = DisplayController(phone) {}
        assertThrows(DisplayFailure::class.java) { controller.setEnabled(0, true) }
        assertTrue(phone.calls.isEmpty())
        assertThrows(DisplayFailure::class.java) { controller.setEnabled(1, true) }
        assertTrue(phone.calls.none { "enable-display" in it })
    }

    @Test fun failedCommandsAreNotSuccessEvenWithZeroExitCode() {
        for (code in listOf(0, 255)) {
            val phone = Phone().apply { rejected = CommandResult(code, "Error: external display management is not available on this device.") }
            assertThrows(DisplayFailure::class.java) { DisplayController(phone) {}.setEnabled(1, true) }
            assertFalse(phone.enabled)
            assertTrue(phone.calls.none { "wakeup" in it })
        }
    }
}
