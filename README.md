# TCP-Client

Android messaging app with post-quantum end-to-end encryption, voice/video calls, and push notifications.

## Features

- **Post-Quantum E2E Encryption** — Messages encrypted with AES-256-GCM, keys exchanged via ML-KEM-768 (Kyber)
- **Server Authentication** — ML-DSA-65 (Dilithium) signature verification, server public key pinned locally
- **Voice & Video Calls** — Real-time encrypted audio/video over UDP with hole punching
- **Push Notifications** — FCM for offline messages and incoming calls (full-screen intent on lock screen)
- **Chat Invites** — Send/accept/deny chat invitations before key exchange (privacy-first)
- **Account Management** — Email confirmation, forgot/reset password, account deletion
- **Heartbeat** — Ping/pong every 30s for connection health monitoring

## Tech Stack

- Java 17, Android SDK (minSdk 24, targetSdk 36)
- TCPSecure 1.1.1 (post-quantum secure transport)
- BouncyCastle (ML-KEM, ML-DSA, X25519, AES-256-GCM)
- Firebase Cloud Messaging
- CameraX + MediaCodec (video calls)
- EncryptedSharedPreferences (credential storage)
- ProGuard/R8 enabled

## Setup

1. **Clone:**
   ```bash
   git clone https://github.com/Rares-boop/TCP-Client-final.git
   ```

2. **Server config:**
   Edit `app/src/main/assets/server_config.properties`:
   ```properties
   server_ip=YOUR_SERVER_IP
   server_port=15555
   ```

3. **Firebase:**
    - Add your Android app in [Firebase Console](https://console.firebase.google.com/) with package `com.vladurares.tcpclient`
    - Download `google-services.json` → place in `app/`

4. **Build & Run:**
   Open in Android Studio → Build → Run

> **Note:** Pre-built APK connects to the default server. To use your own server, clone the repo and update `server_config.properties`.

## Project Structure

```
app/src/main/java/com/vladurares/tcpclient/
├── network/          # TCP connection, FCM, packet routing, video/voice
├── storage/          # Local storage, profile picture cache, secure prefs
├── ui/
│   ├── activities/   # 11 activities (Login, Main, Conversation, Call, Profile, ...)
│   └── adapters/     # RecyclerView adapters (conversations, messages, users, invites)
└── utils/            # Crypto helpers, config reader, NAL utils
```

## Permissions

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | Server communication |
| `RECORD_AUDIO` | Voice calls |
| `CAMERA` | Video calls, profile picture |
| `POST_NOTIFICATIONS` | FCM push notifications (Android 13+) |
| `USE_FULL_SCREEN_INTENT` | Incoming call screen on lock screen |
| `VIBRATE` | Call notifications |

