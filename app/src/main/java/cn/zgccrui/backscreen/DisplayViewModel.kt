package cn.zgccrui.backscreen

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

enum class Connection { NOT_RUNNING, NEED_PERMISSION, CONNECTING, READY }
enum class Operation { NONE, ENABLE, DISABLE }

data class ScreenState(
    val connection: Connection = Connection.NOT_RUNNING,
    val displayId: Int = 1,
    val snapshot: DisplaySnapshot? = null,
    val operation: Operation = Operation.NONE,
    val message: String = "",
    val detail: String = "",
    val warning: Boolean = false,
)

class DisplayViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("display", 0)
    private val mutableState = MutableStateFlow(
        ScreenState(displayId = preferences.getInt("display_id", 1).coerceAtLeast(1)),
    )
    val state = mutableState.asStateFlow()
    private var service: IDisplayService? = null
    private var binding = false
    private var refreshJob: Job? = null
    private var operationJob: Job? = null
    private val commandMutex = Mutex()
    private val serviceArgs = Shizuku.UserServiceArgs(ComponentName(application, DisplayService::class.java))
        .daemon(false)
        .processNameSuffix("display")
        .version(BuildConfig.VERSION_CODE)
        .debuggable(BuildConfig.DEBUG)

    private val receivedListener = Shizuku.OnBinderReceivedListener {
        viewModelScope.launch { reconnect() }
    }
    private val deadListener = Shizuku.OnBinderDeadListener {
        viewModelScope.launch { disconnected() }
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { request, result ->
        if (request == PERMISSION_REQUEST) {
            viewModelScope.launch {
                if (result == PackageManager.PERMISSION_GRANTED) reconnect()
                else mutableState.update {
                    it.copy(connection = Connection.NEED_PERMISSION, message = "尚未获得授权，请在 Shizuku 中允许“背屏开关”。")
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            viewModelScope.launch {
                binding = false
                if (binder == null || !binder.pingBinder()) {
                    disconnected()
                    return@launch
                }
                service = IDisplayService.Stub.asInterface(binder)
                mutableState.update { it.copy(connection = Connection.READY, message = "", detail = "") }
                refresh()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            viewModelScope.launch { disconnected() }
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(receivedListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    fun reconnect() {
        if (!Shizuku.pingBinder()) {
            disconnected()
            return
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                mutableState.update { it.copy(connection = Connection.NEED_PERMISSION, snapshot = null) }
                return
            }
            if (service != null) {
                refresh()
                return
            }
            if (binding) return
            binding = true
            mutableState.update { it.copy(connection = Connection.CONNECTING, message = "") }
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (failure: Exception) {
            binding = false
            mutableState.update {
                it.copy(connection = Connection.NEED_PERMISSION, message = "连接失败，请检查 Shizuku 是否运行并已授权。", detail = failure.toString())
            }
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(PERMISSION_REQUEST) else disconnected()
        } catch (failure: Exception) {
            mutableState.update { it.copy(message = "请打开 Shizuku，在应用管理中授予权限。", detail = failure.toString()) }
        }
    }

    fun setDisplayId(displayId: Int) {
        if (displayId <= 0 || operationJob?.isActive == true) return
        refreshJob?.cancel()
        preferences.edit().putInt("display_id", displayId).apply()
        mutableState.update { it.copy(displayId = displayId, snapshot = null, message = "", detail = "") }
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true || operationJob?.isActive == true) return
        refreshJob = execute(null) { remote, id -> remote.query(id) }
    }

    fun setEnabled(enabled: Boolean) {
        if (operationJob?.isActive == true) return
        operationJob = execute(if (enabled) Operation.ENABLE else Operation.DISABLE) { remote, id ->
            remote.setEnabled(id, enabled)
        }
    }

    private fun execute(operation: Operation?, action: (IDisplayService, Int) -> Bundle): Job? {
        val remote = service ?: return null
        val displayId = state.value.displayId
        if (operation != null) {
            mutableState.update { it.copy(operation = operation, message = "", detail = "") }
        }
        return viewModelScope.launch {
            try {
                // A user action queues behind an in-flight read instead of losing the tap.
                val result = withContext(Dispatchers.IO) {
                    commandMutex.withLock { action(remote, displayId) }
                }
                if (service !== remote || state.value.displayId != displayId) return@launch
                // A read started before an action must not overwrite that action's UI.
                if (operation == null && operationJob?.isActive == true) return@launch
                val snapshot = if (result.getBoolean("known")) {
                    DisplaySnapshot(result.getBoolean("enabled"), result.getString("power"))
                } else null
                mutableState.update {
                    val keepNotice = operation == null && result.getBoolean("ok")
                    it.copy(
                        snapshot = snapshot,
                        operation = if (operation == null) it.operation else Operation.NONE,
                        message = if (keepNotice) it.message else result.getString("message").orEmpty(),
                        detail = if (keepNotice) it.detail else result.getString("detail").orEmpty(),
                        warning = if (keepNotice) it.warning else !result.getBoolean("ok") || result.getBoolean("warning"),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (service !== remote) return@launch
                if (operation == null && operationJob?.isActive == true) return@launch
                service = null
                mutableState.update {
                    it.copy(connection = Connection.NOT_RUNNING, operation = Operation.NONE, snapshot = null,
                        message = "Shizuku 连接已中断，请重新连接。", detail = failure.toString(), warning = true)
                }
            }
        }
    }

    private fun disconnected() {
        service = null
        binding = false
        refreshJob?.cancel()
        operationJob?.cancel()
        mutableState.update { it.copy(connection = Connection.NOT_RUNNING, snapshot = null, operation = Operation.NONE) }
    }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(receivedListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        super.onCleared()
    }

    companion object { private const val PERMISSION_REQUEST = 10 }
}
