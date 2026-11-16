package com.cruiselink.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cruiselink.app.data.ChatMessage
import com.cruiselink.app.data.ChatRoom
import com.cruiselink.app.data.CruiseClient
import com.cruiselink.app.data.PeerUser
import com.cruiselink.app.data.SettingsStore
import com.cruiselink.app.server.HostService
import com.cruiselink.app.server.LanChatServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket
import java.io.ByteArrayOutputStream

data class CruiseUiState(
    val userId: String = "",
    val displayName: String = "",
    val avatarBase64: String = "",
    val serverUrl: String = "",
    val themeMode: String = "dark",
    val isHostRunning: Boolean = false,
    val hostLanUrl: String = "",
    val isConnected: Boolean = false,
    val connectionMessage: String = "尚未連線",
    val peers: List<PeerUser> = emptyList(),
    val rooms: List<ChatRoom> = emptyList(),
    val activeRoom: ChatRoom? = null,
    val messages: List<ChatMessage> = emptyList(),
    val bannerMessage: String? = null
)

class CruiseViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    private val client = CruiseClient()

    private val _uiState = MutableStateFlow(
        CruiseUiState(
            userId = settings.userId,
            displayName = settings.displayName,
            avatarBase64 = settings.avatarBase64,
            serverUrl = settings.serverUrl,
            themeMode = settings.themeMode,
            isHostRunning = HostService.runningUrl != null,
            hostLanUrl = HostService.runningUrl ?: "http://${LanChatServer.getLocalLanIp()}:8787"
        )
    )
    val uiState: StateFlow<CruiseUiState> = _uiState.asStateFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null

    init {
        if (HostService.runningUrl != null) {
            connectToServer("http://127.0.0.1:8787")
        } else if (settings.serverUrl.isNotBlank()) {
            connectToServer(settings.serverUrl)
        }
    }

    fun clearBanner() {
        _uiState.update { it.copy(bannerMessage = null) }
    }

    fun setThemeMode(mode: String) {
        settings.themeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun startHostMode() {
        val ctx = getApplication<Application>()
        HostService.startHost(ctx)
        viewModelScope.launch {
            delay(500)
            val lanUrl = HostService.runningUrl ?: "http://${LanChatServer.getLocalLanIp()}:8787"
            _uiState.update {
                it.copy(
                    isHostRunning = true,
                    hostLanUrl = lanUrl,
                    bannerMessage = "已開啟本機主機模式：$lanUrl"
                )
            }
            connectToServer("http://127.0.0.1:8787")
        }
    }

    fun stopHostMode() {
        HostService.stopHost(getApplication())
        webSocket?.close(1000, "host_stopped")
        webSocket = null
        _uiState.update {
            it.copy(
                isHostRunning = false,
                isConnected = false,
                connectionMessage = "已停止主機",
                bannerMessage = "已關閉本機主機"
            )
        }
    }

    fun connectToClientUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        if (clean.isBlank()) return
        settings.serverUrl = clean
        _uiState.update { it.copy(serverUrl = clean) }
        connectToServer(clean)
    }

    private fun activeBaseUrl(): String =
        if (_uiState.value.isHostRunning) "http://127.0.0.1:8787" else _uiState.value.serverUrl

    private fun connectToServer(baseUrl: String) {
        if (baseUrl.isBlank()) return
        reconnectJob?.cancel()
        webSocket?.close(1000, "reconnect")
        webSocket = null

        viewModelScope.launch {
            val st = _uiState.value
            client.registerPeer(baseUrl, st.userId, st.displayName, st.avatarBase64)
            val peersRes = client.fetchPeers(baseUrl)
            val roomsRes = client.fetchRooms(baseUrl, st.userId)
            _uiState.update { cur ->
                cur.copy(
                    peers = peersRes.getOrElse { cur.peers },
                    rooms = roomsRes.getOrElse { cur.rooms }
                )
            }
        }

        lateinit var sock: WebSocket
        sock = client.connectWebSocket(
            baseUrl = baseUrl,
            userId = _uiState.value.userId,
            onConnected = {
                if (webSocket === sock) {
                    _uiState.update {
                        it.copy(
                            isConnected = true,
                            connectionMessage = if (it.isHostRunning) "主機運行中" else "已連線主機"
                        )
                    }
                }
            },
            onPeersUpdated = { list ->
                _uiState.update { it.copy(peers = list) }
            },
            onRoomsUpdated = { list ->
                val uid = _uiState.value.userId
                val visible = list.filter { it.type == "group" || it.memberIds.contains(uid) }
                _uiState.update { it.copy(rooms = visible) }
            },
            onChatMessage = { msg ->
                _uiState.update { cur ->
                    if (cur.activeRoom?.id == msg.roomId && cur.messages.none { it.id == msg.id }) {
                        cur.copy(messages = cur.messages + msg)
                    } else cur
                }
            },
            onDisconnected = { reason ->
                if (webSocket === sock) {
                    _uiState.update { it.copy(isConnected = false, connectionMessage = reason) }
                    scheduleReconnect(baseUrl)
                }
            }
        )
        webSocket = sock
    }

    private fun scheduleReconnect(baseUrl: String) {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(3500)
            if (_uiState.value.isHostRunning || _uiState.value.serverUrl.isNotBlank()) {
                connectToServer(baseUrl)
            }
        }
    }

    fun updateProfile(displayName: String, avatarBase64: String) {
        settings.displayName = displayName
        settings.avatarBase64 = avatarBase64
        _uiState.update {
            it.copy(
                displayName = settings.displayName,
                avatarBase64 = avatarBase64,
                bannerMessage = "個人名稱與頭貼已儲存"
            )
        }
        val baseUrl = activeBaseUrl()
        if (baseUrl.isNotBlank()) {
            viewModelScope.launch {
                client.registerPeer(baseUrl, settings.userId, settings.displayName, avatarBase64)
            }
        }
    }

    fun setAvatarFromUri(uri: Uri) {
        viewModelScope.launch {
            val b64 = encodeImageFromUri(uri, maxDim = 160, quality = 78)
            if (b64 != null) {
                updateProfile(_uiState.value.displayName, b64)
            } else {
                _uiState.update { it.copy(bannerMessage = "讀取圖片失敗") }
            }
        }
    }

    fun openRoom(room: ChatRoom) {
        val baseUrl = activeBaseUrl()
        _uiState.update { it.copy(activeRoom = room, messages = emptyList()) }
        if (baseUrl.isBlank()) return
        viewModelScope.launch {
            client.fetchMessages(baseUrl, room.id).onSuccess { msgs ->
                _uiState.update { it.copy(messages = msgs) }
            }
        }
    }

    fun closeRoom() {
        _uiState.update { it.copy(activeRoom = null, messages = emptyList()) }
    }

    fun createGroup(name: String) {
        val baseUrl = activeBaseUrl()
        if (baseUrl.isBlank() || name.isBlank()) return
        viewModelScope.launch {
            client.createGroup(baseUrl, "👥 ${name.trim()}", listOf(_uiState.value.userId)).onSuccess { room ->
                openRoom(room)
            }.onFailure { e ->
                _uiState.update { it.copy(bannerMessage = e.message) }
            }
        }
    }

    fun startDm(target: PeerUser) {
        val baseUrl = activeBaseUrl()
        if (baseUrl.isBlank()) return
        viewModelScope.launch {
            client.openDm(baseUrl, _uiState.value.userId, target.id).onSuccess { room ->
                openRoom(room)
            }.onFailure { e ->
                _uiState.update { it.copy(bannerMessage = e.message) }
            }
        }
    }

    fun sendMessage(text: String) {
        val room = _uiState.value.activeRoom ?: return
        val baseUrl = activeBaseUrl()
        if (baseUrl.isBlank() || text.isBlank()) return
        viewModelScope.launch {
            client.sendMessage(baseUrl, room.id, _uiState.value.userId, _uiState.value.displayName, text.trim())
        }
    }

    fun sendImage(uri: Uri) {
        val room = _uiState.value.activeRoom ?: return
        val baseUrl = activeBaseUrl()
        if (baseUrl.isBlank()) return
        viewModelScope.launch {
            val b64 = encodeImageFromUri(uri, maxDim = 720, quality = 72)
            if (b64 != null) {
                client.sendMessage(baseUrl, room.id, _uiState.value.userId, _uiState.value.displayName, "", b64)
            }
        }
    }

    private suspend fun encodeImageFromUri(uri: Uri, maxDim: Int, quality: Int): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val ctx = getApplication<Application>()
                val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: return@runCatching null
                val scale = minOf(maxDim.toFloat() / raw.width, maxDim.toFloat() / raw.height, 1f)
                val w = (raw.width * scale).toInt().coerceAtLeast(1)
                val h = (raw.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }.getOrNull()
        }

    override fun onCleared() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "cleared")
        super.onCleared()
    }
}
