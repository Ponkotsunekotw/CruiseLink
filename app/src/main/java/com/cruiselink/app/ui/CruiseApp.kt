package com.cruiselink.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cruiselink.app.data.ChatMessage
import com.cruiselink.app.data.ChatRoom
import com.cruiselink.app.data.PeerUser
import com.cruiselink.app.theme.CruiseLinkTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CruiseApp(vm: CruiseViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val darkTheme = when (state.themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    CruiseLinkTheme(darkTheme = darkTheme) {
        val activeRoom = state.activeRoom
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (activeRoom != null) {
                            IconButton(onClick = vm::closeRoom) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    title = {
                        if (activeRoom != null) {
                            val title = resolveRoomTitle(activeRoom, state.userId, state.peers)
                            Text(title, style = MaterialTheme.typography.titleMedium)
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AvatarCircle(state.displayName, state.avatarBase64, size = 34.dp)
                                Column {
                                    Text("🚢 CruiseLink 內網通", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = if (state.isConnected) {
                                            "${state.connectionMessage} (${state.peers.count { it.online }} 人在線)"
                                        } else {
                                            state.connectionMessage
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (state.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (activeRoom == null) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.ChatBubble, contentDescription = "聊天") },
                            label = { Text("聊天") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.Group, contentDescription = "成員") },
                            label = { Text("成員") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.Router, contentDescription = "主機") },
                            label = { Text("主機/連線") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "個人") },
                            label = { Text("個人/設定") }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
            ) {
                state.bannerMessage?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = vm::clearBanner) { Text("關閉") }
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    if (activeRoom != null) {
                        RoomChatView(
                            state = state,
                            onSendText = vm::sendMessage,
                            onSendImage = vm::sendImage
                        )
                    } else {
                        when (selectedTab) {
                            0 -> RoomsListView(
                                state = state,
                                onOpenRoom = vm::openRoom,
                                onCreateGroup = vm::createGroup,
                                onGoConnect = { selectedTab = 2 }
                            )
                            1 -> PeersListView(
                                state = state,
                                onStartDm = vm::startDm
                            )
                            2 -> HostConnectView(
                                state = state,
                                onStartHost = vm::startHostMode,
                                onStopHost = vm::stopHostMode,
                                onConnectUrl = vm::connectToClientUrl
                            )
                            3 -> ProfileSettingsView(
                                state = state,
                                onSaveProfile = vm::updateProfile,
                                onPickAvatar = vm::setAvatarFromUri,
                                onThemeChange = vm::setThemeMode
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun resolveRoomTitle(room: ChatRoom, myId: String, peers: List<PeerUser>): String {
    if (room.type != "dm") return room.name
    val otherId = room.memberIds.firstOrNull { it != myId }
    val peer = peers.firstOrNull { it.id == otherId }
    return if (peer != null) "💬 ${peer.displayName}" else room.name
}

@Composable
fun AvatarCircle(name: String, avatarBase64: String, size: Dp = 42.dp) {
    val bmp = remember(avatarBase64) {
        if (avatarBase64.isBlank()) null
        else runCatching {
            val bytes = Base64.decode(avatarBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = name.take(1).ifBlank { "?" }.uppercase(),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun RoomsListView(
    state: CruiseUiState,
    onOpenRoom: (ChatRoom) -> Unit,
    onCreateGroup: (String) -> Unit,
    onGoConnect: () -> Unit
) {
    var showNewGroup by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!state.isConnected && state.rooms.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("尚未連線至內網主機", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "請先到「主機/連線」分頁開啟本機主機，或掃描其他手機主機的 QR Code 連線。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = onGoConnect) { Text("前往設定主機 / 連線") }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.rooms, key = { it.id }) { room ->
                    val otherId = if (room.type == "dm") room.memberIds.firstOrNull { it != state.userId } else null
                    val otherPeer = otherId?.let { id -> state.peers.firstOrNull { it.id == id } }
                    val title = resolveRoomTitle(room, state.userId, state.peers)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRoom(room) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (otherPeer != null) {
                                AvatarCircle(otherPeer.displayName, otherPeer.avatarBase64)
                            } else {
                                AvatarCircle(if (room.id == "lobby") "🚢" else "群", "")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(title, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        room.lastTimestamp,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = room.lastMessage.ifBlank { "點擊進入聊天" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.isConnected) {
            FloatingActionButton(
                onClick = { showNewGroup = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "建立群組")
            }
        }
    }

    if (showNewGroup) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewGroup = false },
            title = { Text("建立新群組") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("群組名稱") },
                    placeholder = { Text("例如：甲板集合、晚餐約會") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNewGroup = false
                        onCreateGroup(name)
                    },
                    enabled = name.isNotBlank()
                ) { Text("建立") }
            },
            dismissButton = {
                TextButton(onClick = { showNewGroup = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PeersListView(
    state: CruiseUiState,
    onStartDm: (PeerUser) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "點擊成員即可開啟 1 對 1 私訊（目前共 ${state.peers.size} 位成員）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(state.peers, key = { it.id }) { peer ->
            val isMe = peer.id == state.userId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isMe) { onStartDm(peer) }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AvatarCircle(peer.displayName, peer.avatarBase64)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isMe) "${peer.displayName} (我)" else peer.displayName,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (peer.online) "🟢 線上" else "⚪ 離線",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (peer.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!isMe) {
                        Text("私訊 →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomChatView(
    state: CruiseUiState,
    onSendText: (String) -> Unit,
    onSendImage: (android.net.Uri) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val imgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onSendImage(uri)
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.messages, key = { it.id }) { msg ->
                MessageBubble(msg, isMe = msg.senderId == state.userId)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = { imgLauncher.launch("image/*") }) {
                Icon(Icons.Default.Image, contentDescription = "傳送圖片")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("輸入訊息...") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            IconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        onSendText(input)
                        input = ""
                    }
                },
                enabled = input.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "傳送")
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, isMe: Boolean) {
    val imgBmp = remember(msg.imageBase64) {
        if (msg.imageBase64.isBlank()) null
        else runCatching {
            val bytes = Base64.decode(msg.imageBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isMe) {
            AvatarCircle(msg.senderName, msg.senderAvatar, size = 32.dp)
            Spacer(Modifier.size(6.dp))
        }
        Surface(
            color = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 270.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = "${msg.senderName}  ${msg.timestamp}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                if (msg.content.isNotBlank()) {
                    Text(msg.content, style = MaterialTheme.typography.bodyMedium)
                }
                if (imgBmp != null) {
                    Spacer(Modifier.height(6.dp))
                    Image(
                        bitmap = imgBmp.asImageBitmap(),
                        contentDescription = "圖片訊息",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        }
        if (isMe) {
            Spacer(Modifier.size(6.dp))
            AvatarCircle(msg.senderName, msg.senderAvatar, size = 32.dp)
        }
    }
}

@Composable
private fun HostConnectView(
    state: CruiseUiState,
    onStartHost: () -> Unit,
    onStopHost: () -> Unit,
    onConnectUrl: (String) -> Unit
) {
    var manualUrl by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents?.trim().orEmpty()
        if (contents.isNotBlank()) {
            manualUrl = contents
            onConnectUrl(contents)
        }
    }
    val qrBitmap = remember(state.hostLanUrl, state.isHostRunning) {
        if (!state.isHostRunning || state.hostLanUrl.isBlank()) null
        else generateQrBitmap(state.hostLanUrl, 480)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("1. 本機作為內網主機（Host）", fontWeight = FontWeight.Bold)
                Text(
                    text = "開啟後，同一個 Wi-Fi / 熱點下的 iPhone（Safari 開啟網頁）與其他 Android 手機都可直接連入溝通，完全不需公網。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.isHostRunning) {
                    Text(
                        text = "主機網址：${state.hostLanUrl}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    qrBitmap?.let { bmp ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "主機 QR Code",
                                modifier = Modifier
                                    .size(200.dp)
                                    .background(Color.White)
                                    .padding(8.dp)
                            )
                        }
                        Text(
                            text = "📱 iPhone 請直接打開內建相機掃描上方 QR Code，即可免裝 App 使用 HTML 網頁版！",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onStopHost,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("停止本機主機")
                    }
                } else {
                    Button(onClick = onStartHost, modifier = Modifier.fillMaxWidth()) {
                        Text("啟動本機內網主機 (:8787)")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("2. 連線至其他 Android 主機（子機模式）", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = manualUrl,
                    onValueChange = { manualUrl = it },
                    label = { Text("主機內網網址") },
                    placeholder = { Text("http://192.168.x.x:8787") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val opts = ScanOptions().apply {
                                setPrompt("掃描主機手機上的 QR Code")
                                setBeepEnabled(false)
                                setOrientationLocked(true)
                            }
                            qrLauncher.launch(opts)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("掃碼連線")
                    }
                    Button(
                        onClick = { onConnectUrl(manualUrl) },
                        enabled = manualUrl.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("手動連線")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSettingsView(
    state: CruiseUiState,
    onSaveProfile: (String, String) -> Unit,
    onPickAvatar: (android.net.Uri) -> Unit,
    onThemeChange: (String) -> Unit
) {
    var name by remember(state.displayName) { mutableStateOf(state.displayName) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPickAvatar(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("我的名稱與頭貼（會同步顯示在所有人的裝置上）", fontWeight = FontWeight.Bold)
                AvatarCircle(name, state.avatarBase64, size = 80.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { avatarPicker.launch("image/*") }) {
                        Text("選擇頭貼照片")
                    }
                    if (state.avatarBase64.isNotBlank()) {
                        OutlinedButton(onClick = { onSaveProfile(name, "") }) {
                            Text("移除頭貼")
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("顯示名稱") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onSaveProfile(name, state.avatarBase64) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("儲存並同步給大家")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("外觀主題", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "跟隨系統", "light" to "淺色", "dark" to "暗黑").forEach { (mode, label) ->
                        FilterChip(
                            selected = state.themeMode == mode,
                            onClick = { onThemeChange(mode) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    }
}

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap? {
    return runCatching {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    }.getOrNull()
}
