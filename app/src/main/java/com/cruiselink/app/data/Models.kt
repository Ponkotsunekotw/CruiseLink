package com.cruiselink.app.data

import kotlinx.serialization.Serializable

@Serializable
data class PeerUser(
    val id: String,
    val displayName: String,
    val avatarBase64: String = "",
    val online: Boolean = true
)

@Serializable
data class ChatRoom(
    val id: String,
    val name: String,
    val type: String = "group", // "group" or "dm"
    val memberIds: List<String> = emptyList(),
    val lastMessage: String = "",
    val lastTimestamp: String = ""
)

@Serializable
data class ChatMessage(
    val id: String,
    val roomId: String,
    val senderId: String,
    val senderName: String,
    val senderAvatar: String = "",
    val content: String,
    val imageBase64: String = "",
    val timestamp: String = ""
)

@Serializable
data class HostInfo(
    val hostName: String = "CruiseLink 主機",
    val port: Int = 8787,
    val peerCount: Int = 0,
    val roomCount: Int = 1
)
