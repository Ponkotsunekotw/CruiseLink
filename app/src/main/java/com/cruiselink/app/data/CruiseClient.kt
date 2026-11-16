package com.cruiselink.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CruiseClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun registerPeer(
        baseUrl: String,
        userId: String,
        displayName: String,
        avatarBase64: String
    ): Result<PeerUser> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("userId", JsonPrimitive(userId))
                put("displayName", JsonPrimitive(displayName))
                put("avatarBase64", JsonPrimitive(avatarBase64))
            }.toString().toRequestBody(jsonType)
            val req = Request.Builder().url("$baseUrl/api/peers/register").post(body).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("註冊失敗 (${resp.code})")
                json.decodeFromString<PeerUser>(text)
            }
        }
    }

    suspend fun fetchPeers(baseUrl: String): Result<List<PeerUser>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$baseUrl/api/peers").get().build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("讀取成員失敗 (${resp.code})")
                json.decodeFromString<List<PeerUser>>(text)
            }
        }
    }

    suspend fun fetchRooms(baseUrl: String, userId: String): Result<List<ChatRoom>> = withContext(Dispatchers.IO) {
        runCatching {
            val enc = URLEncoder.encode(userId, "UTF-8")
            val req = Request.Builder().url("$baseUrl/api/rooms?userId=$enc").get().build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("讀取聊天室失敗 (${resp.code})")
                json.decodeFromString<List<ChatRoom>>(text)
            }
        }
    }

    suspend fun createGroup(baseUrl: String, name: String, memberIds: List<String>): Result<ChatRoom> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("memberIds", buildJsonArray { memberIds.forEach { add(JsonPrimitive(it)) } })
                }.toString().toRequestBody(jsonType)
                val req = Request.Builder().url("$baseUrl/api/rooms/group").post(body).build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("建立群組失敗 (${resp.code})")
                    json.decodeFromString<ChatRoom>(text)
                }
            }
        }

    suspend fun openDm(baseUrl: String, userId: String, targetUserId: String): Result<ChatRoom> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("userId", JsonPrimitive(userId))
                    put("targetUserId", JsonPrimitive(targetUserId))
                }.toString().toRequestBody(jsonType)
                val req = Request.Builder().url("$baseUrl/api/rooms/dm").post(body).build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("開啟私訊失敗 (${resp.code})")
                    json.decodeFromString<ChatRoom>(text)
                }
            }
        }

    suspend fun fetchMessages(baseUrl: String, roomId: String): Result<List<ChatMessage>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val enc = URLEncoder.encode(roomId, "UTF-8")
                val req = Request.Builder().url("$baseUrl/api/rooms/$enc/messages").get().build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("讀取訊息失敗 (${resp.code})")
                    json.decodeFromString<List<ChatMessage>>(text)
                }
            }
        }

    suspend fun sendMessage(
        baseUrl: String,
        roomId: String,
        senderId: String,
        senderName: String,
        content: String,
        imageBase64: String = ""
    ): Result<ChatMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val enc = URLEncoder.encode(roomId, "UTF-8")
            val body = buildJsonObject {
                put("senderId", JsonPrimitive(senderId))
                put("senderName", JsonPrimitive(senderName))
                put("content", JsonPrimitive(content))
                put("imageBase64", JsonPrimitive(imageBase64))
            }.toString().toRequestBody(jsonType)
            val req = Request.Builder().url("$baseUrl/api/rooms/$enc/messages").post(body).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("傳送失敗 (${resp.code})")
                json.decodeFromString<ChatMessage>(text)
            }
        }
    }

    fun connectWebSocket(
        baseUrl: String,
        userId: String,
        onConnected: () -> Unit,
        onPeersUpdated: (List<PeerUser>) -> Unit,
        onRoomsUpdated: (List<ChatRoom>) -> Unit,
        onChatMessage: (ChatMessage) -> Unit,
        onDisconnected: (String) -> Unit
    ): WebSocket {
        val enc = URLEncoder.encode(userId, "UTF-8")
        val wsUrl = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws?userId=$enc"
        val req = Request.Builder().url(wsUrl).build()
        return http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = json.decodeFromString<JsonObject>(text)
                    val type = root["type"]?.jsonPrimitive?.content ?: return
                    val data = root["data"] ?: return
                    when (type) {
                        "peers_updated" -> onPeersUpdated(json.decodeFromJsonElement<List<PeerUser>>(data))
                        "rooms_updated" -> onRoomsUpdated(json.decodeFromJsonElement<List<ChatRoom>>(data))
                        "chat_message" -> onChatMessage(json.decodeFromJsonElement<ChatMessage>(data))
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                onDisconnected("連線已關閉")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onDisconnected(t.localizedMessage ?: "連線中斷")
            }
        })
    }
}
