# A9 Capture (a9-capture) ⚡

**Ultra-Low-Latency HDMI-to-USB Video Capture & Display Monitor for Android Tablets.**

Optimized specifically for the **Samsung Galaxy Tab A9+ (1920x1200 @ 90Hz)** and modern Android tablets, `a9-capture` turns your tablet into a zero-latency, high-refresh-rate portable secondary monitor, game console display, or camera viewfinder via any USB UVC / HDMI-to-USB video capture card.

---

## 🚀 Key Features

* **⚡ Ultra-Low Latency Video Pipeline**:
  - Direct hardware `SurfaceView` overlay rendering bypassing extra buffer copies.
  - Zero-latency frame pacing targeting the full **90 Hz native refresh rate** of the Galaxy Tab A9+.
  - Full **1920x1200** resolution support as well as standard 1080p, 720p, and 4K input downscaling.

* **🖥️ 16:10 Fullscreen Scaling Engine**:
  - **16:10 Stretch (Full A9+)**: Eliminates black bars on 16:10 tablet displays for a true edge-to-edge monitor experience.
  - **16:9 Letterbox (Pixel-Perfect)**: Maintains original aspect ratio for consoles (PS5, Xbox, Switch) and PC HDMI inputs.
  - **Fill Crop**: Crops top/bottom edges to fill the screen without distortion.

* **🎧 Low-Latency HDMI Audio Passthrough**:
  - Direct USB Audio Class (UAC) capture routing to tablet speakers / 3.5mm headphone jack / Bluetooth.
  - Optimized with minimal buffer depth (48 kHz PCM stereo) for real-time gaming sound.

* **📊 Cyberpunk Glass HUD & Live Telemetry**:
  - Real-time VSYNC-synchronized **FPS Counter**.
  - Active input resolution & refresh rate indicator.
  - One-tap 100% uncompressed snapshot capture saved directly to device gallery.
  - Auto-hiding HUD controls (tap anywhere on screen to toggle).

* **🔌 Plug & Play USB Detection**:
  - Listens for USB Video Class (UVC) device attachment and auto-launches camera capture.

---

## 🛠️ Supported Hardware

| Hardware | Support |
| :--- | :--- |
| **Tablet** | Samsung Galaxy Tab A9+ / Tab S8/S9 / any Android 8.0+ tablet |
| **Capture Cards** | Cam Link 4K, MS2109 / MS2130 USB3.0 HDMI Dongles, Genki ShadowCast, Elgato HD60X, TC358743 |
| **Audio** | USB Audio Class (UAC) HDMI embedded audio |

---

## 📦 Building and Installing

### Prerequisites
- JDK 21+
- Android SDK (API 35, Build Tools 35.0.0+)

### Build from Source
```bash
# Clone repository
git clone https://github.com/debarkak/a9-capture.git
cd a9-capture

# Build Debug APK
./gradlew assembleDebug

# Install on connected tablet
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🎮 How to Use

1. Connect your HDMI source (PC, Laptop, Game Console, Camera) to your HDMI-to-USB capture card.
2. Plug the USB-C / OTG connector into your Galaxy Tab A9+.
3. Launch **A9 Capture**.
4. Grant Camera & Audio permissions on first launch.
5. Enjoy ultra-low latency 1920x1200 @ 90Hz HDMI monitoring!

---

## 📄 License
Licensed under the [Apache-2.0 License](LICENSE).
