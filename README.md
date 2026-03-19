# Media Proxy

An Android TV app that bridges HTTP browsable media indexes (nginx autoindex) to WebDAV, enabling media players like [Nova Video Player](https://github.com/nova-video-player/aos-AVP) to browse and stream content directly.

Built for my personal setup where my ISP provides media content via HTTP browsable indexes, but no media player on Android TV supports browsing plain HTTP directory listings natively. This app solves that by running a lightweight local WebDAV server that translates between the two protocols.

Anyone with a similar setup is welcome to use it.

## How It Works

```
ISP HTTP Server (nginx autoindex)
        ↓ (HTTP)
   Media Proxy App (runs on your Android TV)
        ↓ (WebDAV on localhost:8088)
   Nova Video Player
```

- Parses nginx HTML directory listings into proper WebDAV responses
- Proxies video streams with full range request support (seeking works)
- Runs as a lightweight foreground service — minimal CPU/memory overhead
- All data flows through localhost — the proxy just translates protocols

## Features

- **Multi-source support** — Add multiple HTTP index URLs (e.g., TV Series, Movies, Anime) and browse them all as top-level folders
- **Real file sizes** — Fetches actual file sizes via parallel HEAD requests for accurate metadata
- **Full streaming support** — Range requests, seeking, and all common video formats (MKV, MP4, AVI, etc.)
- **Auto-start on boot** — Proxy starts automatically when the TV boots
- **TV remote friendly** — Focus highlights on all buttons, confirmation dialogs for destructive actions
- **Zero dependencies** — Uses NanoHTTPD (embedded), no external services needed
- **Source management** — Add, edit, and delete media sources with persistent storage

## Setup

### Install

1. Download the latest APK from [Releases](https://github.com/KhaledBinAmir/media_proxy/releases)
2. Sideload onto your Android TV via USB or `adb install MediaProxy.apk`
3. Open **Media Proxy**, add your HTTP index URL(s), and press **Start Proxy**

### Connect Nova Video Player

1. Open Nova Video Player
2. Add a new network share:
   - **Protocol:** WebDAV
   - **Server:** `127.0.0.1`
   - **Port:** `8088`
   - **Path:** `/`
   - **Username/Password:** leave empty
3. Browse and stream

## Building from Source

Requirements: JDK 17, Android SDK (API 34)

```bash
# Clone
git clone https://github.com/KhaledBinAmir/media_proxy.git
cd media_proxy

# Set SDK path
echo "sdk.dir=/path/to/android/sdk" > local.properties

# Build
./gradlew assembleDebug

# APK will be at app/build/outputs/apk/debug/app-debug.apk
```

## Configuration

The app stores sources in SharedPreferences. Each source has:
- **Name** — Display name shown in the WebDAV root (e.g., "TV Series", "Movies")
- **URL** — The HTTP browsable index URL (e.g., `http://192.168.1.100:8087`)

Sources persist across app restarts and are used by the boot receiver for auto-start.

## Technical Details

- **WebDAV server:** NanoHTTPD-based, handles PROPFIND, GET, HEAD, OPTIONS
- **Index parsing:** Regex-based parsing of nginx autoindex HTML
- **URL encoding:** Proper handling of special characters, Unicode, and percent-encoded paths
- **File sizes:** Parallel HEAD requests (up to 8 concurrent) for directories with ≤100 files
- **Streaming:** Direct proxy with chunked/fixed-length responses and range request forwarding
- **Service:** Android foreground service with persistent notification

## Compatibility

- **Android:** 5.0+ (API 21)
- **Tested on:** Chromecast with Google TV (armeabi-v7a)
- **HTTP servers:** nginx autoindex (should work with any server producing similar HTML directory listings)
- **Media players:** Nova Video Player (any WebDAV-compatible player should work)

## License

MIT
