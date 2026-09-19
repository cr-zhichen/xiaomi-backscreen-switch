package cn.zgccrui.backscreen

data class CommandResult(val code: Int, val output: String)

fun interface CommandRunner {
    fun run(arguments: List<String>): CommandResult
}

data class DisplaySnapshot(val enabled: Boolean, val power: String?)
data class DisplayOutcome(val snapshot: DisplaySnapshot, val message: String, val warning: Boolean = false)

class DisplayFailure(message: String, val detail: String = "") : Exception(message)

/** Fixed commands only: the privileged service never accepts arbitrary shell text. */
class DisplayController(
    private val runner: CommandRunner,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun query(displayId: Int): DisplaySnapshot {
        validateId(displayId)
        val displays = command("display", "get-displays")
        val entries = Regex("(?m)^Display id (\\d+): (.*)$").findAll(displays).toList()
        if (entries.isEmpty()) throw DisplayFailure("无法识别手机返回的显示器列表。", displays)
        val entry = entries.firstOrNull { it.groupValues[1].toIntOrNull() == displayId }
        if (entry != null) {
            val power = Regex(", state ([A-Z_]+),").find(entry.value)?.groupValues?.get(1)
            return DisplaySnapshot(true, power)
        }

        // Disabled displays disappear from get-displays. Confirm the ID still exists.
        val dump = checked(runner.run(listOf("/system/bin/dumpsys", "display")))
        val block = Regex("(?ms)^  Display $displayId:\\s*\\n(.*?)(?=^  \\S|\\z)")
            .find(dump)?.groupValues?.get(1)
        if (block != null && Regex("(?m)^\\s*mIsEnabled=false\\s*$").containsMatchIn(block)) {
            return DisplaySnapshot(false, "OFF")
        }
        throw DisplayFailure("未找到副屏 $displayId，请在设置中核对编号。")
    }

    fun setEnabled(displayId: Int, enabled: Boolean): DisplayOutcome {
        val before = query(displayId)
        if (!enabled && !before.enabled) return DisplayOutcome(before, "背屏已经关闭。")
        command("display", if (enabled) "enable-display" else "disable-display", displayId.toString())
        val changed = awaitState(displayId) { it.enabled == enabled }
        if (changed.enabled != enabled) {
            throw DisplayFailure("命令已发送，但背屏状态尚未改变，请刷新后重试。")
        }
        if (!enabled) return DisplayOutcome(changed, "背屏已关闭。")

        val powerHelp = try {
            command("power", "help")
        } catch (failure: DisplayFailure) {
            return DisplayOutcome(changed, "背屏已启用，但无法检查唤醒功能。请使用背屏唤醒手势。", true)
        }
        if (!supportsTargetedWakeup(powerHelp)) {
            return DisplayOutcome(changed, "背屏已启用；此系统需要用背屏手势手动唤醒。", true)
        }
        try {
            command("power", "wakeup", "--display-id", displayId.toString())
        } catch (failure: DisplayFailure) {
            throw DisplayFailure("背屏已启用，但唤醒失败。请刷新状态后重试。", failure.detail)
        }
        val awake = awaitState(displayId) { it.enabled && it.power == "ON" }
        return if (awake.enabled && awake.power == "ON") {
            DisplayOutcome(awake, "背屏已开启并唤醒。")
        } else {
            DisplayOutcome(awake, "背屏已启用，暂未确认亮屏。请使用背屏唤醒手势。", true)
        }
    }

    private fun awaitState(displayId: Int, predicate: (DisplaySnapshot) -> Boolean): DisplaySnapshot {
        var snapshot = query(displayId)
        repeat(8) {
            if (predicate(snapshot)) return snapshot
            pause(250)
            snapshot = query(displayId)
        }
        return snapshot
    }

    private fun command(service: String, vararg args: String): String {
        val result = runner.run(listOf("/system/bin/cmd", service) + args)
        val header = if (service == "display") "Display manager commands:" else "Power manager (power) commands:"
        val normalHelp = args.firstOrNull() == "help" && result.code == 255 && result.output.startsWith(header)
        return checked(result, normalHelp)
    }

    private fun checked(result: CommandResult, acceptHelp: Boolean = false): String {
        val hasError = Regex(
            "(?im)^\\s*(?:error:|unknown command|(?:java\\.lang\\.)?securityexception|exception|permission denial|permission denied)",
        ).containsMatchIn(result.output)
        if ((result.code != 0 && !acceptHelp) || hasError) {
            throw DisplayFailure("系统未完成操作，请检查 Shizuku 授权和手机支持情况。", result.output.take(4000))
        }
        return result.output
    }

    companion object {
        fun validateId(displayId: Int) {
            if (displayId <= 0) throw DisplayFailure("副屏编号必须大于 0，不能操作主屏。")
        }

        fun supportsTargetedWakeup(help: String): Boolean {
            val section = Regex("(?ms)^  wakeup(?:[ \\t][^\\r\\n]*)?\\r?\\n(.*?)(?=^  \\S|\\z)")
                .find(help)?.groupValues?.get(1) ?: return false
            return section.contains("--display-id")
        }
    }
}
