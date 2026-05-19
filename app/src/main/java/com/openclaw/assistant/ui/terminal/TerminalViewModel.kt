package com.openclaw.assistant.ui.terminal

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import java.util.concurrent.TimeUnit

data class TerminalOutput(val b64: String)

data class TerminalUiState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val message: String = "Hermes Dashboard terminal is not connected.",
)

class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = BackendRepository.getInstance(application)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var lastCols: Int = 80
    private var lastRows: Int = 24

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState

    private val _outputFlow = MutableSharedFlow<TerminalOutput>(replay = 64, extraBufferCapacity = 64)
    val outputFlow: SharedFlow<TerminalOutput> = _outputFlow

    fun onTerminalReady(cols: Int, rows: Int) {
        lastCols = cols.coerceAtLeast(1)
        lastRows = rows.coerceAtLeast(1)
        if (socket == null) connect()
    }

    fun connect() {
        val endpoint = resolveEndpoint()
        if (endpoint == null) {
            _uiState.value = TerminalUiState(
                connected = false,
                connecting = false,
                message = "Run `hermes dashboard --tui`, then scan an `agentvoice-pair --public-tunnel` QR that includes Terminal.",
            )
            return
        }
        socket?.cancel()
        _uiState.value = TerminalUiState(connecting = true, message = "Connecting to Hermes terminal...")
        val request = Request.Builder().url(endpoint).build()
        socket = client.newWebSocket(request, Listener())
    }

    fun disconnect() {
        socket?.close(1000, "closed by app")
        socket = null
        _uiState.value = TerminalUiState(message = "Disconnected.")
    }

    fun sendInput(data: String) {
        socket?.send(data)
    }

    fun resize(cols: Int, rows: Int) {
        lastCols = cols.coerceAtLeast(1)
        lastRows = rows.coerceAtLeast(1)
        socket?.send("\u001b[RESIZE:$lastCols;$lastRows]")
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private fun resolveEndpoint(): String? {
        val backend = repo.backends.value.firstOrNull {
            it.enabled && it.type == BackendType.HERMES_API_SERVER && !it.terminalUrl.isNullOrBlank() && !it.terminalSessionToken.isNullOrBlank()
        } ?: return null
        val base = backend.terminalUrl?.trim()?.trimEnd('/')?.toHttpUrlOrNull() ?: return null
        val token = backend.terminalSessionToken?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val channel = "agentvoice-${UUID.randomUUID()}"
        val path = if (base.encodedPath.endsWith("/api/pty")) base.encodedPath else "${base.encodedPath.trimEnd('/')}/api/pty"
        val httpUrl = base.newBuilder()
            .encodedPath(path)
            .setQueryParameter("token", token)
            .setQueryParameter("channel", channel)
            .build()
        return httpUrl.toString().replaceFirst(
            if (base.isHttps) "https://" else "http://",
            if (base.isHttps) "wss://" else "ws://",
        )
    }

    private fun emitText(text: String) {
        _outputFlow.tryEmit(TerminalOutput(Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)))
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _uiState.value = TerminalUiState(connected = true, message = "Connected.")
            resize(lastCols, lastRows)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            _outputFlow.tryEmit(TerminalOutput(Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            emitText(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            val status = response?.code?.let { "HTTP $it: " }.orEmpty()
            _uiState.value = TerminalUiState(message = status + (t.message ?: t.javaClass.simpleName))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            _uiState.value = TerminalUiState(message = "Disconnected: $code ${reason.ifBlank { "" }}".trim())
        }
    }
}
