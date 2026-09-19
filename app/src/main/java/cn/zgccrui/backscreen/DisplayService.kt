package cn.zgccrui.backscreen

import android.os.Bundle
import androidx.annotation.Keep
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Created by Shizuku in a shell-UID process, not as an ordinary Android Service. */
@Keep
class DisplayService : IDisplayService.Stub() {
    private val reader = Executors.newSingleThreadExecutor()
    private val controller = DisplayController(CommandRunner { arguments ->
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        val output = reader.submit<String> {
            process.inputStream.bufferedReader().use { stream ->
                val text = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (text.length + count > 2_000_000) throw DisplayFailure("系统返回的数据过大。")
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
        }
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                throw DisplayFailure("等待系统响应超时，请刷新后重试。")
            }
            CommandResult(process.exitValue(), output.get(1, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
            output.cancel(true)
        }
    })

    @Synchronized
    override fun query(displayId: Int): Bundle = respond(displayId) {
        DisplayOutcome(controller.query(displayId), "")
    }

    @Synchronized
    override fun setEnabled(displayId: Int, enabled: Boolean): Bundle = respond(displayId) {
        controller.setEnabled(displayId, enabled)
    }

    private fun respond(displayId: Int, block: () -> DisplayOutcome): Bundle = try {
        val outcome = block()
        snapshotBundle(outcome.snapshot).apply {
            putBoolean("ok", true)
            putString("message", outcome.message)
            putBoolean("warning", outcome.warning)
        }
    } catch (failure: Exception) {
        val snapshot = runCatching { controller.query(displayId) }.getOrNull()
        (snapshot?.let(::snapshotBundle) ?: Bundle()).apply {
            putBoolean("ok", false)
            putString("message", (failure as? DisplayFailure)?.message ?: "与系统通信失败，请刷新后重试。")
            putString("detail", ((failure as? DisplayFailure)?.detail ?: failure.toString()).take(4000))
        }
    }

    private fun snapshotBundle(snapshot: DisplaySnapshot) = Bundle().apply {
        putBoolean("known", true)
        putBoolean("enabled", snapshot.enabled)
        putString("power", snapshot.power)
    }

    override fun destroy() {
        reader.shutdownNow()
        exitProcess(0)
    }
}
