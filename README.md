# CruiseLink 🚢

**CruiseLink** 是一款專為「無網際網路環境」（例如郵輪海上航行、戶外露營、機艙、地下室或純區網）設計的**離線區域網路即時通訊與檔案互傳 App**。

只要一台 Android 手機開啟熱點（或所有裝置連上同一個區域網路／Wi-Fi），無需任何網際網路連線或外部伺服器，即可讓多台 Android、iOS 與電腦裝置即時聊天、傳送照片與分享檔案。

---

## ✨ 核心功能

- **完全離線運作（Zero Internet Required）**：只需手機熱點或區域網路，不經過任何雲端伺服器。
- **Android 雙模式**：
  - **Host（房主／伺服器模式）**：在 Android 背景啟動輕量 HTTP + WebSocket 伺服器（預設埠號 `8787`），作為區網聊天與檔案中心。
  - **Client（訪客／連線模式）**：透過掃描房主 QR Code 或輸入區域網路網址加入聊天室。
- **跨平台免安裝網頁端（iOS / 電腦皆可用）**：
  - 房主模式內建 HTML5 Web 客戶端（`assets/web/index.html`）。
  - iPhone、iPad 或筆電只要連上同一個熱點／Wi-Fi，用 Safari 或瀏覽器掃描房主的 QR Code 或開啟 `http://<房主區網IP>:8787`，即可直接加入聊天與傳檔，無須安裝任何 App。
- **多聊天室與私訊**：支援公共大廳、群組聊天室與在線成員一對一對話。
- **檔案與圖片分享**：支援區網內快速上傳、瀏覽與下載圖片及檔案，並可自動儲存 Host 狀態。

---

## 📱 快速使用教學

### 1. 準備連線環境（任選一種）
- **方式 A（戶外／郵輪推薦）**：由擔任「房主」的 Android 手機開啟 **Wi-Fi 個人熱點**，其他人的手機（Android / iPhone）或電腦連上這個熱點。
- **方式 B（同一個 Wi-Fi）**：所有人的裝置連上同一個區域網路 Wi-Fi。

### 2. 房主（Host）開房步驟
1. 在房主 Android 手機安裝並開啟 **CruiseLink**。
2. 切換到 **「Host 開房模式」** 並啟動背景伺服器。
3. 畫面上會顯示本機區域網路網址（例如 `http://192.168.43.1:8787`）與 **加入用 QR Code**。

### 3. 其他人加入步驟
- **Android 用戶（已安裝 CruiseLink）**：
  開啟 App → 選擇 **「Client 加入模式」** → 點擊掃描房主的 QR Code（或手動輸入網址）即可加入。
- **iPhone / iPad / 電腦用戶（免安裝 App）**：
  直接使用手機相機掃描房主的 QR Code，或在瀏覽器（Safari / Chrome）輸入房主顯示的網址（例如 `http://192.168.43.1:8787`），輸入暱稱即可開始聊天與傳檔。

---

## 🛠️ 開發與自行編譯

### 環境需求
- Android Studio / JDK 17
- Android SDK（`minSdk 26` 以上）

### 編譯 Debug APK
```powershell
cd CruiseLink
.\gradlew.bat assembleDebug
```
產出的 APK 位於 `app/build/outputs/apk/debug/app-debug.apk`。

---

## 📂 專案結構

```text
CruiseLink/
├── app/src/main/
│   ├── assets/web/index.html          # 內建跨平台 HTML5 網頁端（供 iOS / 瀏覽器使用）
│   └── java/com/cruiselink/app/
│       ├── MainActivity.kt            # 應用程式進入點
│       ├── data/                      # 資料模型、WebSocket/HTTP 用戶端與設定儲存
│       ├── server/
│       │   ├── HostService.kt         # Android 前景服務（維持背景伺服器運行）
│       │   └── LanChatServer.kt       # 內嵌 HTTP + WebSocket 區域網路伺服器（Port 8787）
│       └── ui/                        # Jetpack Compose 介面與 ViewModel
└── build.gradle.kts
```

---

## ⚠️ 使用注意事項
- **無網際網路提示**：當手機連上沒有上網功能的熱點時，系統可能會彈出「此 Wi-Fi 沒有網際網路連線，是否保持連線？」提示，請務必選擇 **「保持連線」**。
- **電池最佳化**：建議房主手機將 CruiseLink 的背景電池設為「無限制」，避免長時間待機導致背景服務休眠。
