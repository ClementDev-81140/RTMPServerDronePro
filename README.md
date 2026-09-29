# 🚁 RTMP Server Drone PRO

Android app that turns the phone into a **local RTMP server (port 1935)** ready to receive the live
video feed of a drone (**DJI Fly**, Autel, Parrot…) and relay it in real time to several remote
destinations (**Telegram RTMPS**, YouTube, Restream, Facebook) with a very low latency (300-500 ms).

---

## ✨ Main features

- **Universal inbound RTMP server (port 1935)** — compatible with the strict network requirements of
  DJI Fly (dynamic chunk size 4096 negotiation, ACK acknowledgements, AMF0 commands).
- **Multi-destination relay & failover** — up to 3 active platforms streaming at the same time.
- **Telegram RTMPS support (port 443 / TLS)** — TLS with SNI and clock alignment with the hardware
  encoder of the drone.
- **Local H.264 hardware preview (MediaCodec)** — on-the-fly AVCC ➔ Annex-B conversion rendered
  directly on a SurfaceView, plus a fullscreen immersive mode (⛶).
- **Local recording (MP4 / MediaMuxer)** — the incoming H.264 + AAC stream is copied to an MP4 file
  without any re-encoding. Files are stored in the app folder, or in the public *Movies* folder so
  they show up in the gallery (Android 10+). The file is finalised automatically when the drone
  disconnects.
- **Battery saver mode** — pauses the hardware video decoding (the biggest CPU/GPU consumer), slows
  down the statistics refresh to 3 s and keeps the notification compact, while the relay keeps
  running at full quality.
- **Configuration screen** — RTMP port, stream path, streaming defaults (preview / recording /
  battery saver), recording storage, keep-screen-on and an About section.
- **Local persistence (Room DB)** — full management of the destinations (add, edit, duplicate,
  priority ⭐). The list always comes from the database: starting or stopping the server never hides
  a destination again.
- **Modern dark design** — clean interface (#0E0E10) with real-time status indicators (OFFLINE /
  WAITING FOR DRONE / LIVE).

---

## 🛠️ Technical stack

- **Languages**: Java 11, C++17 (NDK/CMake)
- **Min SDK**: 24 (Android 7.0) | **Target SDK**: 34 (Android 14)
- **Architecture**: Android foreground service, MediaCodec, MediaMuxer, Room database,
  Java NIO non-blocking sockets.

---

## 🚀 Usage

1. Launch the app on your Android phone.
2. Connect the drone to the phone hotspot or to the same Wi-Fi network.
3. Open the **Configuration** screen (⚙) and adjust the RTMP port / stream path if needed.
4. Add your destinations (e.g. Telegram URL `rtmps://…` + stream key) and enable them.
5. Tap **START SERVER** — the RTMP server waits for the drone.
6. In DJI Fly, set the live stream to `rtmp://[PHONE_IP]:1935/live` (the exact URL is displayed at
   the bottom of the main screen and can be copied).
7. The status badge turns to **LIVE** as soon as the drone signal arrives; recordings appear in the
   folder shown in the Configuration screen.
