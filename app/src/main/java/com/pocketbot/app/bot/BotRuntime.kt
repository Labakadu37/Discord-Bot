package com.pocketbot.app.bot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnState { OFFLINE, CONNECTING, ONLINE, ERROR }

data class RuntimeState(
    val conn: ConnState = ConnState.OFFLINE,
    val botName: String? = null,
    val applicationId: String? = null,
    val guildCount: Int = 0,
    val error: String? = null,
)

data class LogLine(val time: String, val text: String, val isError: Boolean)

/** État partagé entre le service (qui fait tourner le bot) et l'interface. */
object BotRuntime {
    private const val MAX_LOGS = 300
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    fun update(transform: (RuntimeState) -> RuntimeState) = _state.update(transform)

    fun log(text: String, isError: Boolean = false) {
        val line = LogLine(timeFormat.format(Date()), text, isError)
        _logs.update { (it + line).takeLast(MAX_LOGS) }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
