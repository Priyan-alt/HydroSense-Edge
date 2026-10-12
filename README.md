# HydroSense Edge

<p align="center">
  <strong>Non-Invasive Optical PPG Hydration Monitoring Band & Android Mobile Application</strong><br>
  <em>Real-time physiological hydration tracking using dual-wavelength optical perfusion index and 6-axis motion compensation on ESP32-S3.</em>
</p>

---

## 📌 Overview

**HydroSense Edge** is an end-to-end edge-computing biomedical prototype designed for non-invasive, experimental hydration estimation. Using dual-wavelength photoplethysmography (Red & Infrared PPG) from a MAX30102 sensor and 6-axis inertial motion tracking from an MPU6050 IMU, the system calculates an optical **Hydration Index (HI)** relative to the wearer's personalized baseline:

$$\text{HI} = 100 \times \frac{\text{PI}_{\text{current}}}{\text{PI}_{\text{baseline}}}$$

The system pairs an **ESP32-S3** microcontroller band with a modern **Android mobile app** via Bluetooth Low Energy (BLE), featuring on-demand measurement requests, adaptive motion filtering, signal quality verification, persistent database logging, and real-time dashboard analytics.

> **⚠️ Disclaimer:** *HydroSense Edge is an experimental prototype and biomedical research proxy. The optical Hydration Index is a physiological research metric and is NOT intended for medical diagnosis or clinical treatment.*

---

## ✨ Features

### 📱 Android Application (Jetpack Compose)
- **Interactive Dashboard:** Live Hydration Index hero gauge with dynamic color-coding (`HYDRATED ≥ 90`, `BORDERLINE 70–90`, `DEHYDRATED < 70`).
- **On-Demand "Measure Now" Flow:** Tapping *Measure Now* sends command `'m'` over BLE, launches a 15-second window, streams live countdown/progress feedback, and validates new readings.
- **Robust Fault Rejection:** Rejects stale readings and handles motion artifacts, poor skin contact, optical saturation, missing calibration, timeouts (25s guard), and disconnections.
- **Personal Baseline Calibration:** Guided 8-window (~2 min) calibration while well-hydrated, recording reference PI, HR, and IR contact levels with coefficient of variation (CV) scatter validation.
- **Live Sensor Telemetry:** Real-time graphs for Perfusion Index (PI), Signal Quality Index (SQI), MAX30102 IR baseline, and MPU6050 IMU motion variance.
- **Local Database (Room DB):** Stores all accepted measurements with timestamps, classification, and metrics; supports status filtering and historical deletion.
- **Device & BLE Manager:** Auto-scanner for `HydroSense Edge`, connection lifecycle management, MTU 512 negotiation, and real-time raw BLE packet logging.

### ⚡ ESP32-S3 Firmware (Arduino C++)
- **Dual Independent I2C Buses:** 
  - `Wire` (GPIO 4 SDA / GPIO 5 SCL): Dedicated to MAX30102 PPG (400 kHz).
  - `Wire1` (GPIO 8 SDA / GPIO 9 SCL): Dedicated to SSD1306 OLED & MPU6050 IMU (400 kHz).
- **Multi-Axis Normalized LMS Filter:** 6-axis adaptive filtering to attenuate moderate motion artifacts.
- **Adaptive Posture & Activity Gate:** Establishes resting arm orientation baseline and rejects windows with angular changes $> 45^\circ$ or excessive acceleration ($> 0.05\,\text{g}$).
- **Non-Volatile Storage (Preferences):** Persists personal reference baseline to flash memory across power cycles.
- **OLED Display & Serial Telemetry:** 128x64 display for standalone operation, accompanied by high-speed CSV diagnostic streams.
- **BLE GATT Server:** Standard service `4fafc...` and characteristic `beb54...` supporting read, write, and notifications.

---

## 🔌 Hardware Wiring & Pinout

| Module / Sensor | Pin | ESP32-S3 Pin | Bus / Interface | Details |
| :--- | :--- | :--- | :--- | :--- |
| **MAX30102 PPG** | SDA | **GPIO 4** | `Wire` (Primary) | Dedicated high-speed PPG bus |
| **MAX30102 PPG** | SCL | **GPIO 5** | `Wire` (Primary) | 400 kHz clock |
| **MPU6050 IMU** | SDA | **GPIO 8** | `Wire1` (Auxiliary) | Shared sensor & display bus |
| **MPU6050 IMU** | SCL | **GPIO 9** | `Wire1` (Auxiliary) | 400 kHz clock |
| **SSD1306 OLED** | SDA | **GPIO 8** | `Wire1` (Auxiliary) | 128×64 resolution (`0x3C`) |
| **SSD1306 OLED** | SCL | **GPIO 9** | `Wire1` (Auxiliary) | Shared with IMU |
| **User Button** | KEY | **GPIO 0** | `INPUT_PULLUP` | Short press = Calib, Hold 2s = Clear |
| **Power Supply** | VIN | **3.3V / 5V** | System Power | Match sensor breakout regulator |
| **Ground** | GND | **GND** | System Ground | Common ground plane |

---

## 📡 BLE Protocol Specification

- **Device Name:** `HydroSense Edge`
- **Service UUID:** `4fafc201-1fb5-459e-8fcc-c5c9c331914b`
- **Characteristic UUID:** `beb5483e-36e1-4688-b7f5-ea07361b26a8` (READ | WRITE | NOTIFY)
- **CCCD UUID:** `00002902-0000-1000-8000-00805f9b34fb`

### Downlink Commands (App ➔ ESP32)
| Command | ASCII | Description |
| :---: | :---: | :--- |
| `m` | `0x6D` | **Measure Now:** Starts an on-demand 15-second fresh measurement cycle. |
| `c` | `0x63` | **Calibrate:** Initiates 8-window personal baseline calibration. |
| `x` | `0x78` | **Clear Baseline:** Erases saved reference from flash memory. |
| `s` | `0x73` | **Status Query:** Requests peripheral status and calibration state. |
| `p` | `0x70` | **Stream Toggle:** Toggles 100 Hz raw sample streaming. |

### Uplink Telemetry (ESP32 ➔ App)
- **On-Demand Start:** `{"type":"measure_start","duration":15,"requestId":1}`
- **Progress (1 Hz):** `{"type":"measure_progress","percent":46,"secondsRemaining":8,"status":"ANALYZING","angle":1.8,"requestId":1}`
- **Measurement Result:**
  ```json
  {
    "type": "reading",
    "reading": 1,
    "requestId": 1,
    "accepted": true,
    "status": "HYDRATED",
    "hi": 94.2,
    "hiSmooth": 93.8,
    "pi": 1.85,
    "hr": 71.4,
    "rmssd": 38.2,
    "sqi": 0.842,
    "motion": "STABLE",
    "ir": 128450,
    "dcRed": 95200,
    "irShift": 3.2,
    "contact": 1.0,
    "refPI": 1.97,
    "refHR": 70.0,
    "refIR": 124500,
    "refCV": 14.8,
    "nValid": 16
  }
  ```
- **Rejection / Error:** `{"type":"measure_error","status":"MOTION","reason":"Wrist movement detected during measurement"}`

---

## 📂 Repository Structure

```
HydroSense-Edge/
├── apk/
│   └── HydroSenseEdge-debug.apk          # Prebuilt verified Android debug APK (~18.4 MB)
├── app/
│   ├── build.gradle.kts                  # App module build configuration & dependencies
│   ├── proguard-rules.pro                # R8 / ProGuard rules
│   └── src/main/
│       ├── AndroidManifest.xml           # BLE & Bluetooth permissions (Android 8 - 15)
│       ├── java/com/hydrosense/edge/
│       │   ├── HydroSenseApp.kt          # Application class & Room DB initialization
│       │   ├── MainActivity.kt           # Single activity entry point
│       │   ├── data/
│       │   │   ├── ble/                  # BleManager, BlePacketParser, BleConnectionState
│       │   │   ├── db/                   # AppDatabase, HydrationDao, HydrationRecordEntity
│       │   │   ├── model/                # HydrationReading, MeasurementUiState, CalibrationInfo
│       │   │   └── repository/           # HydrationRepository (Measure Now state machine)
│       │   └── ui/
│       │       ├── MainScreen.kt         # Navigation scaffolding (Bottom bar)
│       │       ├── components/           # Components (MeasureNowCard, HeroCard, MetricTile)
│       │       ├── screens/              # Dashboard, LiveSensor, History, DeviceDebug screens
│       │       ├── theme/                # Color palettes, typography, Material3 theme
│       │       └── viewmodel/            # HydrationViewModel & Factory
│       └── res/                          # App icons, vectors, strings, themes
├── firmware/
│   └── HydroSense_Edge_Phase1/
│       └── HydroSense_Edge_Phase1.ino    # Compile-ready ESP32-S3 firmware sketch
├── gradle/
│   ├── libs.versions.toml                # Dependency version catalog
│   └── wrapper/                          # Gradle wrapper binary and properties
├── build.gradle.kts                      # Root Gradle build script
├── settings.gradle.kts                   # Project module inclusion
├── gradle.properties                     # JVM and AndroidX configuration
├── gradlew / gradlew.bat                 # Gradle wrapper scripts
├── .gitignore                            # Excludes build caches, secrets, and temp files
└── README.md                             # Project documentation
```

---

## 🚀 Building & Installation

### 1. Prebuilt APK
A compile-verified debug APK is included directly in this repository:
```bash
adb install apk/HydroSenseEdge-debug.apk
```

### 2. Building Android App from Source
- **Prerequisites:** Android Studio Ladybug (2024.2.1+) or newer, JDK 17, Android SDK API 35.
- Open project in Android Studio or build via command line:
  ```bash
  # Windows
  .\gradlew.bat assembleDebug

  # macOS / Linux
  ./gradlew assembleDebug
  ```
- Output binary: `app/build/outputs/apk/debug/app-debug.apk`.

### 3. Flashing ESP32-S3 Firmware
- **Prerequisites:** Arduino IDE 2.x or VS Code + PlatformIO.
- **Board Settings:**
  - Board: **ESP32S3 Dev Module**
  - Flash Size: **16MB (128Mb)**
  - PSRAM: **OPI PSRAM**
  - USB CDC On Boot: **Enabled**
- **Required Arduino Libraries:**
  - `SparkFun MAX3010x Pulse and Proximity Sensor Library` (v1.1.2+)
  - `Adafruit SSD1306` (v2.5.9+)
  - `Adafruit GFX Library` (v1.11.9+)
  - *Built-in ESP32 core:* `Wire`, `Preferences`, `BLEDevice`
- Open `firmware/HydroSense_Edge_Phase1/HydroSense_Edge_Phase1.ino` and upload.

---

## 📄 License
This project is released for academic, research, and open-source prototyping purposes.
