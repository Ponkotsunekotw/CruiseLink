package com.cruiselink.app.server

import android.content.Context
import android.os.Build
import com.cruiselink.app.data.ChatMessage
import com.cruiselink.app.data.ChatRoom
import com.cruiselink.app.data.HostInfo
import com.cruiselink.app.data.PeerUser
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Serializable
private data class PersistedHostState(
    val peers: Map<String, PeerUser> = emptyMap(),
    val rooms: List<ChatRoom> = emptyList(),
    val messages: Map<String, List<ChatMessage>> = emptyMap()
)

class LanChatServer(
    private val context: Context,
    private val port: Int = 8787
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val peers = ConcurrentHashMap<String, PeerUser>()
    private val rooms = ConcurrentHashMap<String, ChatRoom>()
    private val messagesByRoom = ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>>()
    private val wsClients = CopyOnWriteArrayList<WsClient>()

    private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null
    @Volatile
    var isRunning: Boolean = false
        private set

    private val stateFile = File(context.filesDir, "cruiselink_host_state.json")
    private val stateLock = Any()

    init {
        loadState()
        if (!rooms.containsKey("lobby")) {
            rooms["lobby"] = ChatRoom(
                id = "lobby",
                name = "🚢 郵輪大廳",
                type = "group",
                memberIds = emptyList(),
                lastMessage = "歡迎使用 CruiseLink 內網通訊！",
                lastTimestamp = nowHm()
            )
            messagesByRoom.putIfAbsent("lobby", CopyOnWriteArrayList())
        }
    }

    private fun nowHm(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun loadState() {
        if (!stateFile.exists()) return
        runCatching {
            val raw = stateFile.readText(Charsets.UTF_8)
            val saved = json.decodeFromString<PersistedHostState>(raw)
            saved.peers.forEach { (id, p) -> peers[id] = p.copy(online = false) }
            saved.rooms.forEach { r -> rooms[r.id] = r }
            saved.messages.forEach { (rid, list) ->
                messagesByRoom[rid] = CopyOnWriteArrayList(list)
            }
        }
    }

    private fun saveState() {
        synchronized(stateLock) {
            runCatching {
                val snapshot = PersistedHostState(
                    peers = peers.toMap(),
                    rooms = rooms.values.toList(),
                    messages = messagesByRoom.mapValues { it.value.takeLast(200) }
                )
                stateFile.writeText(json.encodeToString(snapshot), Charsets.UTF_8)
            }
        }
    }

    fun start() {
        if (isRunning) return
        val ss = ServerSocket(port)
        ss.reuseAddress = true
        serverSocket = ss
        pool = Executors.newCachedThreadPool()
        isRunning = true

        pool?.execute {
            while (isRunning && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    pool?.execute { handleClient(client) }
                } catch (_: Exception) {
                    if (!isRunning) break
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        wsClients.forEach { it.close() }
        wsClients.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool?.shutdownNow()
        pool = null
        saveState()
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val headerBytes = readHeaderBytes(input) ?: run {
                socket.close()
                return
            }
            val headerText = String(headerBytes, Charsets.UTF_8)
            val lines = headerText.split("\r\n")
            if (lines.isEmpty()) {
                socket.close()
                return
            }
            val reqParts = lines[0].split(" ")
            val method = reqParts.getOrNull(0)?.uppercase() ?: "GET"
            val fullPath = reqParts.getOrNull(1) ?: "/"
            val path = fullPath.substringBefore("?")
            val query = fullPath.substringAfter("?", "")

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                val idx = line.indexOf(":")
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            if (headers["upgrade"]?.equals("websocket", ignoreCase = true) == true && path == "/ws") {
                handleWebSocket(socket, input, output, headers, query)
                return
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)
            val bodyStr = String(bodyBytes, Charsets.UTF_8)

            routeHttp(output, method, path, query, bodyStr)
            output.flush()
            socket.close()
        } catch (_: Exception) {
            runCatching { socket.close() }
        }
    }

    private fun readHeaderBytes(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            buf.write(b)
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) break else if (b == '\r'.code) 1 else 0
                else -> 0
            }
            if (buf.size() > 65536) return null
        }
        return buf.toByteArray()
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n <= 0) break
            read += n
        }
        return if (read == length) data else data.copyOf(read)
    }

    private fun routeHttp(
        output: OutputStream,
        method: String,
        path: String,
        query: String,
        body: String
    ) {
        if (method == "OPTIONS") {
            sendResponse(output, "204 No Content", "text/plain", ByteArray(0))
            return
        }

        if (method == "GET" && (path == "/" || path == "/index.html")) {
            val html = runCatching {
                context.assets.open("web/index.html").bufferedReader(Charsets.UTF_8).readText()
            }.getOrElse { "<html><body><h1>CruiseLink Web Client Missing</h1></body></html>" }
            sendResponse(output, "200 OK", "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
            return
        }

        if (method == "GET" && path == "/api/info") {
            val info = HostInfo(
                hostName = Build.MODEL ?: "CruiseLink 主機",
                port = port,
                peerCount = peers.size,
                roomCount = rooms.size
            )
            sendJson(output, "200 OK", json.encodeToString(info))
            return
        }

        if (method == "GET" && path == "/api/peers") {
            val list = peers.values.sortedByDescending { it.online }
            sendJson(output, "200 OK", json.encodeToString(list))
            return
        }

        if (method == "POST" && path == "/api/peers/register") {
            val obj = json.decodeFromString<JsonObject>(body)
            val userId = obj["userId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val displayName = obj["displayName"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "旅伴" }
            val avatar = obj["avatarBase64"]?.jsonPrimitive?.content.orEmpty()
            if (userId.isBlank()) {
                sendJson(output, "400 Bad Request", """{"error":"userId required"}""")
                return
            }
            val peer = PeerUser(
                id = userId,
                displayName = displayName,
                avatarBase64 = avatar,
                online = true
            )
            peers[userId] = peer
            saveState()
            broadcast("peers_updated", json.encodeToString(peers.values.sortedByDescending { it.online }))
            sendJson(output, "200 OK", json.encodeToString(peer))
            return
        }

        if (method == "GET" && path == "/api/rooms") {
            val uid = queryParam(query, "userId")
            val visible = rooms.values.filter { room ->
                room.type == "group" || uid.isBlank() || room.memberIds.contains(uid)
            }.sortedWith(compareBy<ChatRoom> { it.id != "lobby" }.thenBy { it.type != "group" }.thenBy { it.name })
            sendJson(output, "200 OK", json.encodeToString(visible))
            return
        }

        if (method == "POST" && path == "/api/rooms/group") {
            val obj = json.decodeFromString<JsonObject>(body)
            val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "新群組" }
            val members = obj["memberIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val room = ChatRoom(
                id = "grp-${UUID.randomUUID().toString().take(8)}",
                name = name,
                type = "group",
                memberIds = members,
                lastMessage = "群組已建立",
                lastTimestamp = nowHm()
            )
            rooms[room.id] = room
            messagesByRoom.putIfAbsent(room.id, CopyOnWriteArrayList())
            saveState()
            broadcast("rooms_updated", json.encodeToString(rooms.values.toList()))
            sendJson(output, "201 Created", json.encodeToString(room))
            return
        }

        if (method == "POST" && path == "/api/rooms/dm") {
            val obj = json.decodeFromString<JsonObject>(body)
            val u1 = obj["userId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val u2 = obj["targetUserId"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (u1.isBlank() || u2.isBlank()) {
                sendJson(output, "400 Bad Request", """{"error":"userId and targetUserId required"}""")
                return
            }
            val pair = listOf(u1, u2).sorted()
            val dmId = "dm:${pair[0]}:${pair[1]}"
            val room = rooms.getOrPut(dmId) {
                val n1 = peers[u1]?.displayName ?: u1
                val n2 = peers[u2]?.displayName ?: u2
                ChatRoom(
                    id = dmId,
                    name = "$n1 & $n2",
                    type = "dm",
                    memberIds = pair,
                    lastMessage = "已開啟私訊",
                    lastTimestamp = nowHm()
                )
            }
            messagesByRoom.putIfAbsent(dmId, CopyOnWriteArrayList())
            saveState()
            broadcast("rooms_updated", json.encodeToString(rooms.values.toList()))
            sendJson(output, "200 OK", json.encodeToString(room))
            return
        }

        val msgMatch = Regex("^/api/rooms/([^/]+)/messages$").matchEntire(path)
        if (msgMatch != null) {
            val roomId = java.net.URLDecoder.decode(msgMatch.groupValues[1], "UTF-8")
            if (method == "GET") {
                val rawList = messagesByRoom[roomId]?.toList() ?: emptyList()
                val enriched = rawList.takeLast(200).map { msg ->
                    val p = peers[msg.senderId]
                    if (p != null) {
                        msg.copy(senderName = p.displayName, senderAvatar = p.avatarBase64)
                    } else {
                        msg
                    }
                }
                sendJson(output, "200 OK", json.encodeToString(enriched))
                return
            }
            if (method == "POST") {
                val obj = json.decodeFromString<JsonObject>(body)
                val senderId = obj["senderId"]?.jsonPrimitive?.content?.trim().orEmpty()
                val content = obj["content"]?.jsonPrimitive?.content?.trim().orEmpty()
                val imageBase64 = obj["imageBase64"]?.jsonPrimitive?.content.orEmpty()
                if (senderId.isBlank() || (content.isBlank() && imageBase64.isBlank())) {
                    sendJson(output, "400 Bad Request", """{"error":"senderId and content/image required"}""")
                    return
                }
                val peer = peers[senderId]
                val msg = ChatMessage(
                    id = "m-${UUID.randomUUID().toString().take(8)}",
                    roomId = roomId,
                    senderId = senderId,
                    senderName = peer?.displayName ?: obj["senderName"]?.jsonPrimitive?.content ?: "旅伴",
                    senderAvatar = peer?.avatarBase64 ?: "",
                    content = content,
                    imageBase64 = imageBase64,
                    timestamp = nowHm()
                )
                messagesByRoom.getOrPut(roomId) { CopyOnWriteArrayList() }.add(msg)
                rooms[roomId]?.let { r ->
                    rooms[roomId] = r.copy(
                        lastMessage = if (content.isNotBlank()) content else "[圖片]",
                        lastTimestamp = msg.timestamp
                    )
                }
                saveState()
                broadcast("chat_message", json.encodeToString(msg))
                broadcast("rooms_updated", json.encodeToString(rooms.values.toList()))
                sendJson(output, "201 Created", json.encodeToString(msg))
                return
            }
        }

        sendJson(output, "404 Not Found", """{"error":"Not found"}""")
    }

    private fun queryParam(query: String, key: String): String {
        if (query.isBlank()) return ""
        return query.split("&").mapNotNull {
            val idx = it.indexOf("=")
            if (idx > 0 && it.substring(0, idx) == key) {
                java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8")
            } else null
        }.firstOrNull().orEmpty()
    }

    private fun sendJson(output: OutputStream, status: String, jsonBody: String) {
        sendResponse(output, status, "application/json; charset=utf-8", jsonBody.toByteArray(Charsets.UTF_8))
    }

    private fun sendResponse(output: OutputStream, status: String, contentType: String, body: ByteArray) {
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        output.write(header)
        if (body.isNotEmpty()) output.write(body)
    }

    private fun handleWebSocket(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        query: String
    ) {
        val key = headers["sec-websocket-key"] ?: run {
            socket.close()
            return
        }
        val acceptRaw = MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.UTF_8))
        val accept = Base64.getEncoder().encodeToString(acceptRaw)
        val handshake = (
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        output.write(handshake)
        output.flush()

        socket.soTimeout = 0
        val uid = queryParam(query, "userId")
        val client = WsClient(socket, output, uid)
        wsClients.add(client)

        if (uid.isNotBlank()) {
            peers[uid]?.let {
                peers[uid] = it.copy(online = true)
                broadcast("peers_updated", json.encodeToString(peers.values.sortedByDescending { p -> p.online }))
            }
        }

        try {
            while (isRunning && !socket.isClosed) {
                val b1 = input.read()
                if (b1 == -1) break
                val b2 = input.read()
                if (b2 == -1) break
                val opcode = b1 and 0x0F
                val masked = (b2 and 0x80) != 0
                var len = (b2 and 0x7F).toLong()
                if (len == 126L) {
                    val hi = input.read()
                    val lo = input.read()
                    if (hi == -1 || lo == -1) break
                    len = ((hi shl 8) or lo).toLong()
                } else if (len == 127L) {
                    var ext = 0L
                    for (i in 0 until 8) {
                        val b = input.read()
                        if (b == -1) return
                        ext = (ext shl 8) or b.toLong()
                    }
                    len = ext
                }
                val mask = if (masked) readExact(input, 4) else ByteArray(0)
                val payload = if (len > 0 && len <= 2_000_000) readExact(input, len.toInt()) else ByteArray(0)
                if (masked) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }
                if (opcode == 0x8) break // close
                if (opcode == 0x9) client.sendFrame(0xA, payload) // ping -> pong
            }
        } catch (_: Exception) {
        } finally {
            wsClients.remove(client)
            client.close()
            if (uid.isNotBlank() && wsClients.none { it.userId == uid }) {
                peers[uid]?.let {
                    peers[uid] = it.copy(online = false)
                    broadcast("peers_updated", json.encodeToString(peers.values.sortedByDescending { p -> p.online }))
                }
            }
        }
    }

    private fun broadcast(type: String, dataJson: String) {
        val raw = buildJsonObject {
            put("type", JsonPrimitive(type))
            put("data", json.parseToJsonElement(dataJson))
        }.toString().toByteArray(Charsets.UTF_8)

        val dead = mutableListOf<WsClient>()
        for (c in wsClients) {
            if (!c.sendText(raw)) {
                dead.add(c)
            }
        }
        wsClients.removeAll(dead)
    }

    private class WsClient(
        private val socket: Socket,
        private val output: OutputStream,
        val userId: String
    ) {
        private val writeLock = Any()

        fun sendText(utf8: ByteArray): Boolean = sendFrame(0x1, utf8)

        fun sendFrame(opcode: Int, payload: ByteArray): Boolean {
            synchronized(writeLock) {
                return try {
                    val out = ByteArrayOutputStream()
                    out.write(0x80 or opcode)
                    when {
                        payload.size <= 125 -> out.write(payload.size)
                        payload.size <= 65535 -> {
                            out.write(126)
                            out.write((payload.size shr 8) and 0xFF)
                            out.write(payload.size and 0xFF)
                        }
                        else -> {
                            out.write(127)
                            val len = payload.size.toLong()
                            for (i in 7 downTo 0) {
                                out.write(((len shr (i * 8)) and 0xFF).toInt())
                            }
                        }
                    }
                    out.write(payload)
                    output.write(out.toByteArray())
                    output.flush()
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        fun getLocalLanIp(): String {
            runCatching {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                val candidates = mutableListOf<String>()
                for (iface in ifaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                candidates.add(ip)
                            }
                        }
                    }
                }
                if (candidates.isNotEmpty()) {
                    return candidates.firstOrNull { it.startsWith("192.168.") } ?: candidates.first()
                }
            }
            return "127.0.0.1"
        }
    }
}
