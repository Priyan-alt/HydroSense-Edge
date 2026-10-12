/*
 * =====================================================================
 *  HydroSense Edge — Phase 1 Final (Firmware)
 *  Board: ESP32-S3 Dev Module (N16R8, 16MB Flash, 8MB OPI PSRAM)
 *
 *  Hardware Connections:
 *    MAX30102 PPG : SDA -> GPIO 4, SCL -> GPIO 5 (Wire)
 *    MPU6050 IMU  : SDA -> GPIO 8, SCL -> GPIO 9 (Wire1)
 *    SSD1306 OLED : SDA -> GPIO 8, SCL -> GPIO 9 (Wire1, 128x64, 0x3C)
 *    BOOT Button  : GPIO 0 (Short press = Calibrate, Hold 2s = Clear)
 *
 *  BLE Specifications:
 *    Device Name : "HydroSense Edge"
 *    Service UUID: 4fafc201-1fb5-459e-8fcc-c5c9c331914b
 *    Char UUID   : beb5483e-36e1-4688-b7f5-ea07361b26a8 (READ | WRITE | NOTIFY)
 *    CCCD UUID   : 00002902-0000-1000-8000-00805f9b34fb
 *
 *  On-Demand "Measure Now" Protocol:
 *    - App writes 'm' to Characteristic -> Firmware starts fresh 15s window
 *    - Emits: {"type":"measure_start","duration":15,"requestId":%lu}
 *    - Emits: {"type":"measure_progress","percent":%d,"secondsRemaining":%d,"status":"%s"}
 *    - Emits: {"type":"reading", ... final window results ...}
 *    - Error handling: rejects if uncalibrated, poor contact, motion/posture change
 *
 *  Libraries Required:
 *    - SparkFun MAX3010x Pulse and Proximity Sensor Library (v1.1.2+)
 *    - Adafruit SSD1306 (v2.5.9+)
 *    - Adafruit GFX Library (v1.11.9+)
 *    - Built-in ESP32: Wire, Preferences, BLEDevice, BLEServer, BLEUtils, BLE2902
 * =====================================================================
 */

#include <Arduino.h>
#include <Wire.h>
#include <Preferences.h>
#include <math.h>
#include <string.h>
#include <algorithm>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include "MAX30105.h"

// ESP32 BLE Headers
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// =====================================================================
// USER CONFIG & HARDWARE PINOUT
// =====================================================================
static const int      PIN_SDA_PPG = 4;
static const int      PIN_SCL_PPG = 5;
static const int      PIN_SDA_AUX = 8;
static const int      PIN_SCL_AUX = 9;
static const uint32_t I2C_HZ      = 400000;
static const int      PIN_BUTTON  = 0;

static const uint8_t  LED_BRIGHTNESS    = 0x3F;
static const uint32_t CONTACT_IR_THRESH = 50000;   // Threshold for finger/wrist contact
static const uint32_t SATURATION_LEVEL  = 260000;  // Photodiode saturation limit

// BLE UUIDs — Must match Android App Exactly
#define BLE_DEVICE_NAME "HydroSense Edge"
#define SERVICE_UUID    "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHAR_UUID       "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// Posture & Motion Rejection Limits (Phase 1)
static const float    MOTION_SD_A_MAX        = 0.05f;   // g, stillness threshold
static const float    MOTION_SD_G_MAX        = 10.0f;   // deg/s, gyro stillness
static const uint32_t POSTURE_STABLE_MS      = 1500UL;  // Settle time before window
static const float    POSTURE_CHANGE_MAX_DEG = 45.0f;   // Max acceptable tilt change
static const uint32_t GATE_RETRY_MS          = 8000UL;
static const int      GATE_WARNING_REJECTS   = 5;

// Multi-Axis LMS Motion Filter Parameters
static const bool     USE_DMACR_FOR_HYDRATION = false;
static const int      LMS_AXES               = 6;
static const int      LMS_TAPS               = 5;
static const float    LMS_MU                 = 0.04f;
static const float    LMS_EPS                = 0.001f;

static const float    SQI_MIN                = 0.50f;   // Min acceptable SQI
static const float    FS_TOLERANCE           = 0.10f;   // Sample rate drift tolerance
static const float    HR_MIN                 = 40.0f;
static const float    HR_MAX                 = 180.0f;

// Hydration Thresholds (Relative to personal baseline)
static const float    HI_MARGIN_BORDER_PCT   = 10.0f;   // HI < 90 = Borderline
static const float    HI_MARGIN_LOW_PCT      = 30.0f;   // HI < 70 = Dehydrated
static const float    NOISE_MULT             = 0.5f;
static const float    REF_MAX_CV_PCT         = 40.0f;   // Baseline scatter limit
static const float    IR_SHIFT_MAX_PCT       = 35.0f;   // Fit difference vs calibration
static const int      HI_SMOOTH_N            = 5;       // Final median window count
static const int      HI_SMOOTH_MIN          = 1;       // First preliminary result

static const float    W_CONTACT              = 0.25f;
static const float    W_PEAKS                = 0.35f;
static const float    W_STAB                 = 0.20f;
static const float    W_MOTION               = 0.20f;

// =====================================================================
// CONSTANTS
// =====================================================================
constexpr int FS          = 100;
constexpr int WINDOW_SEC  = 15;
constexpr int N_WIN       = FS * WINDOW_SEC;  // 1500 samples
constexpr int SETTLE      = 2 * FS;           // 2s settling
constexpr int MAX_PEAKS   = 64;
constexpr int REF_WINDOWS = 8;                // 8 windows for baseline (2 min)
constexpr int SMOOTH_MAX  = 5;

static_assert(REF_WINDOWS >= 3, "REF_WINDOWS too small");

static const float MIN_IBI = 0.30f;
static const float MAX_IBI = 2.00f;
static const float PPG_BP_LOW_HZ  = 0.5f;
static const float PPG_BP_HIGH_HZ = 5.0f;

enum Status : uint8_t {
  ST_MEASURING = 0, ST_OK, ST_NO_CONTACT, ST_MOTION, ST_UNRELIABLE, ST_SATURATED, ST_RATE, ST_REFIT
};
static const char* STATUS_TXT[] = {
  "Measuring", "OK", "Wear band snugly", "Keep still", "Signal unreliable",
  "Sensor saturated", "Sample rate error", "Refit band like at calibration"
};
static const char* HEAD_TXT[] = {
  "MEASURING", "OK", "WEAR BAND", "KEEP STILL", "UNRELIABLE", "TOO BRIGHT", "RATE ERROR", "REFIT BAND"
};

enum Hyd : uint8_t { HY_NONE = 0, HY_OK, HY_BORDER, HY_LOW };
static const char* HYD_SHORT[] = { "--", "HYDRATED", "BORDERLINE", "DEHYDRATED" };
static const char* HYD_LONG[]  = {
  "--",
  "HYDRATED - optical ratio optimal",
  "BORDERLINE - low water reserve, consider drinking",
  "DEHYDRATED - significant optical index drop, drink water"
};

// =====================================================================
// DATA STRUCTURES
// =====================================================================
struct Biquad {
  float b0, b1, b2, a1, a2, z1, z2;
  void reset() { z1 = 0; z2 = 0; }
  void setLowpass(float fc, float fs) {
    const float w0 = 2.0f * (float)M_PI * fc / fs, c = cosf(w0);
    const float al = sinf(w0) / (2.0f * 0.70710678f), a0 = 1.0f + al;
    b0 = (1.0f - c) * 0.5f / a0; b1 = (1.0f - c) / a0; b2 = b0;
    a1 = -2.0f * c / a0; a2 = (1.0f - al) / a0; reset();
  }
  void setHighpass(float fc, float fs) {
    const float w0 = 2.0f * (float)M_PI * fc / fs, c = cosf(w0);
    const float al = sinf(w0) / (2.0f * 0.70710678f), a0 = 1.0f + al;
    b0 = (1.0f + c) * 0.5f / a0; b1 = -(1.0f + c) / a0; b2 = b0;
    a1 = -2.0f * c / a0; a2 = (1.0f - al) / a0; reset();
  }
  float process(float x) {
    const float y = b0 * x + z1;
    z1 = b1 * x - a1 * y + z2;
    z2 = b2 * x - a2 * y;
    return y;
  }
};

struct Result {
  uint32_t id, tMs, requestId;
  uint8_t  status;
  bool     accepted;
  float    contactFrac, fsMeas;
  uint32_t sat;
  float    dcRed, dcIr, aRed, aIr, piRed, piIr, piComb, rAC;
  float    hr, rmssd, width, rise, area, ampCV;
  float    sdA, sdG, sqi;
  float    postureDeg;
  float    piRaw, piClean;
  int      nPeaks, nValid, nBeats;
  bool     hiValid;
  float    hi, hiSmooth, margin, irShift;
  uint8_t  hyd;
  int      reading;
};

struct Reference {
  bool  valid, calibrating;
  int   count;
  float piBuf[REF_WINDOWS], hrBuf[REF_WINDOWS], irBuf[REF_WINDOWS];
  float refPI, refHR, refIR, refCV;
};

struct MotionLMS {
  float w[LMS_AXES][LMS_TAPS];
  float hist[LMS_AXES][LMS_TAPS];
  float prev[LMS_AXES];
  float hp[LMS_AXES];

  void reset() { memset(this, 0, sizeof(*this)); }

  float process(float desired, const float in[LMS_AXES]) {
    float x[LMS_AXES];
    float energy = LMS_EPS;
    const float scale[LMS_AXES] = {0.08f, 0.08f, 0.08f, 15.0f, 15.0f, 15.0f};

    for (int a = 0; a < LMS_AXES; a++) {
      const float hpNow = in[a] - prev[a] + 0.9875f * hp[a];
      prev[a] = in[a];
      hp[a] = hpNow;
      x[a] = constrain(hpNow / scale[a], -4.0f, 4.0f);
    }

    for (int a = 0; a < LMS_AXES; a++) {
      for (int k = LMS_TAPS - 1; k > 0; k--) hist[a][k] = hist[a][k - 1];
      hist[a][0] = x[a];
      for (int k = 0; k < LMS_TAPS; k++) energy += hist[a][k] * hist[a][k];
    }

    float predicted = 0.0f;
    for (int a = 0; a < LMS_AXES; a++)
      for (int k = 0; k < LMS_TAPS; k++) predicted += w[a][k] * hist[a][k];

    const float error = desired - predicted;
    const float step = LMS_MU * error / energy;

    for (int a = 0; a < LMS_AXES; a++)
      for (int k = 0; k < LMS_TAPS; k++) w[a][k] += step * hist[a][k];

    return error;
  }
};

// =====================================================================
// GLOBALS & PERIPHERALS
// =====================================================================
Adafruit_SSD1306 display(128, 64, &Wire1, -1, 400000UL, 400000UL);
MAX30105         ppg;
Preferences      prefs;

static bool    oledOk   = false;
static bool    mpuOk    = false;
static bool    ppgOk    = false;
static uint8_t oledAddr = 0x3C;
static uint8_t mpuAddr  = 0x68;

static uint32_t rawRed[N_WIN], rawIr[N_WIN];
static float    fRed[N_WIN],   fIr[N_WIN], cleanRed[N_WIN], cleanIr[N_WIN];
static float    aMag[N_WIN],   gMag[N_WIN], tmpBuf[N_WIN];

static Biquad   hpR, lpR, hpI, lpI;
static MotionLMS lmsRed, lmsIr;

static int      idx = 0;
static int      goodRun = 0, lostRun = 0;
static bool     inContact = false, firstWindow = true;
static uint32_t satCount = 0, badContact = 0, winStartMs = 0, windowCounter = 0;
static float    dcOffR = 0, dcOffI = 0;
static uint32_t lastRed = 0, lastIr = 0;

static float    aNow = 1.0f, gNow = 0.0f;
static float    axNow = 0.0f, ayNow = 0.0f, azNow = 1.0f;
static float    gxNow = 0.0f, gyNow = 0.0f, gzNow = 0.0f;
static float    postureAngleDeg = 0.0f;
static float    postureMaxWindowDeg = 0.0f;
static int      postureRejectStreak = 0;
static float    baseAx = 0.0f, baseAy = 0.0f, baseAz = 1.0f;
static bool     postureBaselineValid = false;

static uint32_t lastMpuMs = 0;
static bool     gateReady = false;
static uint32_t postureStableSince = 0;
static uint32_t gateAttemptSince = 0;
static int      gateRejectStreak = 0;
static bool     gateWarning = false;

static Result    lastR;
static bool      haveR = false;
static Reference ref;
static float     hiRing[SMOOTH_MAX];
static int       hiCount = 0, hiHead = 0, readingNo = 0;

static uint32_t contactLostMs = 0;
static uint8_t  verdictHyd    = HY_NONE;
static float    verdictHI     = NAN;

static bool     streamRaw   = false;
static uint32_t lastLiveMs  = 0;
static uint32_t lastDispMs  = 0;
static bool     btnDown     = false;
static bool     btnLongDone = false;
static uint32_t btnMs       = 0;

// BLE Server & Characteristic
static BLEServer*         pServer         = nullptr;
static BLECharacteristic* pCharacteristic = nullptr;
static bool               deviceConnected = false;

// On-demand "Measure Now" state machine
static bool     onDemandMeasuring   = false;
static uint32_t onDemandRequestId   = 0;
static uint32_t onDemandStartMs     = 0;
static uint32_t lastMeasureProgMs   = 0;

// Forward Declarations
static void startCalibration();
static void clearReference();
static void printStatus();
static void printHelp();
static void sendBleJson(const char* json);
static void startOnDemandMeasurement();
static void handleCommand(char c);

// =====================================================================
// BLE CALLBACKS
// =====================================================================
class HydroServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) override {
    deviceConnected = true;
    Serial.println("# BLE Client connected to HydroSense Edge");
  }
  void onDisconnect(BLEServer* pServer) override {
    deviceConnected = false;
    Serial.println("# BLE Client disconnected. Restarting advertising...");
    pServer->getAdvertising()->start();
  }
};

class HydroCharacteristicCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pChar) override {
    std::string rx = pChar->getValue();
    if (rx.length() > 0) {
      Serial.printf("# BLE Received: %s\n", rx.c_str());
      const char cmd = rx[0];
      handleCommand(cmd);
    }
  }
};

// =====================================================================
// SMALL HELPERS
// =====================================================================
static inline float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

static float medianOf(float* a, int n) {
  if (n <= 0) return NAN;
  std::sort(a, a + n);
  return (n & 1) ? a[n / 2] : 0.5f * (a[n / 2 - 1] + a[n / 2]);
}

static float percentile(const float* x, int n, float p) {
  if (n <= 0) return NAN;
  memcpy(tmpBuf, x, sizeof(float) * n);
  std::sort(tmpBuf, tmpBuf + n);
  int k = (int)(p * (n - 1) + 0.5f);
  if (k < 0) k = 0;
  if (k > n - 1) k = n - 1;
  return tmpBuf[k];
}

static void meanSd(const float* x, int n, float& mean, float& sd) {
  double s = 0;
  for (int i = 0; i < n; i++) s += x[i];
  const double m = s / n;
  double v = 0;
  for (int i = 0; i < n; i++) { const double d = x[i] - m; v += d * d; }
  mean = (float)m;
  sd = (n > 1) ? (float)sqrt(v / (n - 1)) : 0.0f;
}

static int argminS(const float* x, float sign, int a, int b) {
  int best = a;
  for (int i = a; i <= b; i++) if (sign * x[i] < sign * x[best]) best = i;
  return best;
}

static float marginBorder() { return fmaxf(HI_MARGIN_BORDER_PCT, NOISE_MULT * ref.refCV); }
static float marginLow()    { return marginBorder() + (HI_MARGIN_LOW_PCT - HI_MARGIN_BORDER_PCT); }

static uint8_t hydOf(float hiSmooth) {
  if (hiSmooth >= 100.0f - marginBorder()) return HY_OK;
  if (hiSmooth >= 100.0f - marginLow())    return HY_BORDER;
  return HY_LOW;
}

static void formatFloatJson(char* dst, size_t maxLen, float v, int prec = 2) {
  if (isnan(v) || !isfinite(v)) {
    snprintf(dst, maxLen, "null");
  } else {
    char fmt[16];
    snprintf(fmt, sizeof(fmt), "%%.%df", prec);
    snprintf(dst, maxLen, fmt, v);
  }
}

static void sendBleJson(const char* json) {
  if (deviceConnected && pCharacteristic != nullptr) {
    pCharacteristic->setValue((uint8_t*)json, strlen(json));
    pCharacteristic->notify();
  }
}

// =====================================================================
// STORAGE (Preferences in NVS)
// =====================================================================
static void saveReference() {
  if (!prefs.begin("hydband", false)) return;
  prefs.putBool("valid", true);
  prefs.putFloat("pi", ref.refPI);
  prefs.putFloat("hr", ref.refHR);
  prefs.putFloat("ir", ref.refIR);
  prefs.putFloat("cv", ref.refCV);
  prefs.end();
}

static void loadReference() {
  memset(&ref, 0, sizeof(ref));
  if (!prefs.begin("hydband", false)) return;
  ref.valid = prefs.getBool("valid", false);
  if (ref.valid) {
    ref.refPI = prefs.getFloat("pi", 0);
    ref.refHR = prefs.getFloat("hr", 0);
    ref.refIR = prefs.getFloat("ir", 0);
    ref.refCV = prefs.getFloat("cv", 0);
    if (!(ref.refPI > 0) || !(ref.refIR > 0)) ref.valid = false;
  }
  prefs.end();
}

static void eraseReference() {
  if (prefs.begin("hydband", false)) { prefs.clear(); prefs.end(); }
}

// =====================================================================
// I2C & SENSOR SETUP
// =====================================================================
static void i2cScan(TwoWire &bus, const char* name, bool isAux) {
  Serial.printf("# I2C scan (%s):\n", name);
  bool any = false;
  for (uint8_t a = 1; a < 127; a++) {
    bus.beginTransmission(a);
    if (bus.endTransmission() == 0) {
      Serial.printf("#   found 0x%02X\n", a);
      any = true;
      if (isAux) {
        if (a == 0x3C || a == 0x3D) oledAddr = a;
        if (a == 0x68 || a == 0x69) mpuAddr = a;
      }
    }
  }
  if (!any) Serial.println("#   nothing found - check wiring/pins");
}

static bool mpuWrite(uint8_t reg, uint8_t val) {
  Wire1.beginTransmission(mpuAddr);
  Wire1.write(reg);
  Wire1.write(val);
  return Wire1.endTransmission() == 0;
}

static bool mpuInit() {
  Wire1.beginTransmission(mpuAddr);
  Wire1.write(0x75);
  if (Wire1.endTransmission(false) != 0) return false;
  if (Wire1.requestFrom((int)mpuAddr, 1) != 1) return false;
  const uint8_t who = Wire1.read();
  Serial.printf("# MPU WHO_AM_I = 0x%02X (0x68 expected)\n", who);
  if (!mpuWrite(0x6B, 0x00)) return false;
  delay(100);
  mpuWrite(0x1A, 0x03);
  mpuWrite(0x1B, 0x00);
  mpuWrite(0x1C, 0x00);
  return true;
}

static bool mpuRead() {
  Wire1.beginTransmission(mpuAddr);
  Wire1.write(0x3B);
  if (Wire1.endTransmission(false) != 0) return false;
  if (Wire1.requestFrom((int)mpuAddr, 14) != 14) return false;
  int16_t v[7];
  for (int i = 0; i < 7; i++) {
    const uint8_t hi = Wire1.read();
    const uint8_t lo = Wire1.read();
    v[i] = (int16_t)((hi << 8) | lo);
  }

  axNow = v[0] / 16384.0f;
  ayNow = v[1] / 16384.0f;
  azNow = v[2] / 16384.0f;
  gxNow = v[4] / 131.0f;
  gyNow = v[5] / 131.0f;
  gzNow = v[6] / 131.0f;

  aNow = sqrtf(axNow * axNow + ayNow * ayNow + azNow * azNow);
  gNow = sqrtf(gxNow * gxNow + gyNow * gyNow + gzNow * gzNow);

  if (postureBaselineValid && aNow > 0.001f) {
    const float cx = axNow / aNow;
    const float cy = ayNow / aNow;
    const float cz = azNow / aNow;
    const float dot = constrain(cx * baseAx + cy * baseAy + cz * baseAz, -1.0f, 1.0f);
    postureAngleDeg = acosf(dot) * 180.0f / (float)M_PI;
  } else {
    postureAngleDeg = 0.0f;
  }
  return true;
}

// =====================================================================
// WINDOW & CONTACT STATE
// =====================================================================
static void resetFilters(float redFirst, float irFirst) {
  hpR.setHighpass(PPG_BP_LOW_HZ, FS);  lpR.setLowpass(PPG_BP_HIGH_HZ, FS);
  hpI.setHighpass(PPG_BP_LOW_HZ, FS);  lpI.setLowpass(PPG_BP_HIGH_HZ, FS);
  dcOffR = redFirst; dcOffI = irFirst;
  lmsRed.reset();
  lmsIr.reset();
}

static void clearWindow() {
  idx = 0; satCount = 0; badContact = 0; postureMaxWindowDeg = 0.0f;
}

static void clearSmoothing() {
  hiCount = 0; hiHead = 0; verdictHyd = HY_NONE; verdictHI = NAN;
}

static void dropContact() {
  inContact = false; goodRun = 0; lostRun = 0; firstWindow = true; haveR = false;
  gateReady = false;
  postureStableSince = 0;
  gateAttemptSince = 0;
  gateRejectStreak = 0;
  gateWarning = false;
  postureRejectStreak = 0;
  postureBaselineValid = false;
  contactLostMs = millis();
  clearWindow();

  if (onDemandMeasuring) {
    onDemandMeasuring = false;
    sendBleJson("{\"type\":\"measure_error\",\"status\":\"WEAR_BAND\",\"reason\":\"Sensor contact lost during measurement. Fasten band snugly.\"}");
  }

  Serial.println("# Contact lost (IR below threshold) - window reset");
}

// =====================================================================
// PEAK DETECTION & FEATURE EXTRACTION
// =====================================================================
static int findPeaks(const float* x, int s, int e, float sign, float thr, int* pk, int maxP) {
  int n = 0;
  const int minD = (int)(MIN_IBI * FS);
  for (int i = s + 1; i < e - 1; i++) {
    const float v = sign * x[i];
    if (v > thr && v > sign * x[i - 1] && v >= sign * x[i + 1]) {
      if (n > 0 && (i - pk[n - 1]) < minD) {
        if (v > sign * x[pk[n - 1]]) pk[n - 1] = i;
      } else if (n < maxP) {
        pk[n++] = i;
      }
    }
  }
  return n;
}

static int evalPeaks(const int* pk, int nPk, float* ibi, bool* valid) {
  const int ni = nPk - 1;
  if (ni < 2) return 0;
  float tmp[MAX_PEAKS];
  for (int i = 0; i < ni; i++) { ibi[i] = (pk[i + 1] - pk[i]) / (float)FS; tmp[i] = ibi[i]; }
  const float med = medianOf(tmp, ni);
  int nv = 0;
  for (int i = 0; i < ni; i++) {
    valid[i] = (ibi[i] >= MIN_IBI && ibi[i] <= MAX_IBI && fabsf(ibi[i] - med) <= 0.3f * med);
    if (valid[i]) nv++;
  }
  return nv;
}

static void printReadingBlock(const Result& r) {
  Serial.println();
  Serial.println("==============================");
  Serial.printf("HYDRATION READING #%d\n", r.reading);
  Serial.println("------------------------------");
  Serial.printf("PI = %.2f %%\n", r.piComb);
  Serial.printf("HR = %.1f BPM\n", r.hr);
  Serial.printf("RMSSD = %.1f ms\n", r.rmssd);
  Serial.printf("SQI = %.3f\n", r.sqi);
  Serial.printf("Wrist position change = %.1f deg\n", r.postureDeg);
  Serial.printf("PI raw = %.2f %% | PI motion-cleaned = %.2f %%\n", r.piRaw, r.piClean);
  Serial.printf("Motion = %.4f g\n", r.sdA);
  Serial.printf("IR = %.0f  (%+.0f%% vs reference)\n", r.dcIr, r.irShift);
  Serial.printf("Reference PI = %.2f %%  (scatter %.1f %%)\n", ref.refPI, ref.refCV);
  Serial.printf("Hydration Index (this window) = %.1f\n", r.hi);
  if (hiCount >= HI_SMOOTH_MIN) {
    Serial.printf("Hydration Index (median of last %d) = %.1f\n", hiCount, r.hiSmooth);
    Serial.printf("RESULT: %s%s\n", HYD_LONG[r.hyd],
                  (hiCount < HI_SMOOTH_N) ? "   [preliminary]" : "");
    Serial.printf("(HYDRATED >= %.0f, BORDERLINE >= %.0f, DEHYDRATED below)\n",
                  100.0f - marginBorder(), 100.0f - marginLow());
  } else {
    Serial.printf("RESULT: measuring (%d of %d windows needed)\n", hiCount, HI_SMOOTH_MIN);
  }
  Serial.println("Prototype index - optical PPG hydration proxy.");
  Serial.println("==============================");
}

static void startCalibration() {
  clearWindow(); haveR = false; clearSmoothing();
  ref.calibrating = true; ref.count = 0;
  char buf[128];
  snprintf(buf, sizeof(buf), "{\"type\":\"calibration\",\"progress\":0,\"total\":%d,\"msg\":\"Started\"}", REF_WINDOWS);
  sendBleJson(buf);
  Serial.printf("# CALIBRATION started. Wear the band snugly and stay relaxed.\n"
                "# Calibrate only while WELL HYDRATED (%d windows of %ds).\n",
                REF_WINDOWS, WINDOW_SEC);
}

static void clearReference() {
  ref.valid = false; ref.calibrating = false; ref.count = 0;
  clearSmoothing(); readingNo = 0; haveR = false;
  eraseReference();
  sendBleJson("{\"type\":\"status\",\"status\":\"CLEARED\",\"msg\":\"Reference cleared\"}");
  Serial.println("# Reference cleared from flash. Calibrate again before use.");
}

// =====================================================================
// WINDOW PROCESSING & BLE NOTIFICATION
// =====================================================================
static void processWindow() {
  static int   pkP[MAX_PEAKS], pkN[MAX_PEAKS];
  static float ibiP[MAX_PEAKS], ibiN[MAX_PEAKS];
  static bool  valP[MAX_PEAKS], valN[MAX_PEAKS];
  static float amps[MAX_PEAKS], rises[MAX_PEAKS], widths[MAX_PEAKS], areas[MAX_PEAKS];

  Result r;
  memset(&r, 0, sizeof(r));
  r.id = ++windowCounter;
  r.tMs = millis();
  r.requestId = onDemandMeasuring ? onDemandRequestId : 0;
  r.hr = r.rmssd = r.width = r.rise = r.area = r.ampCV = NAN;
  r.hi = NAN; r.hiSmooth = NAN;
  r.postureDeg = postureMaxWindowDeg;
  r.piRaw = r.piClean = NAN;

  const int s = firstWindow ? SETTLE : 0;
  const int e = N_WIN;
  const int n = e - s;

  const uint32_t elapsed = r.tMs - winStartMs;
  r.fsMeas = elapsed > 0 ? (N_WIN * 1000.0f) / (float)elapsed : 0.0f;
  r.sat = satCount;
  r.contactFrac = 1.0f - (float)badContact / (float)N_WIN;

  double sr = 0, si = 0;
  for (int i = s; i < e; i++) { sr += rawRed[i]; si += rawIr[i]; }
  r.dcRed = (float)(sr / n);
  r.dcIr  = (float)(si / n);

  const float aRedRaw = percentile(fRed + s, n, 0.95f) - percentile(fRed + s, n, 0.05f);
  const float aIrRaw  = percentile(fIr + s, n, 0.95f)  - percentile(fIr + s, n, 0.05f);
  const float aRedClean = percentile(cleanRed + s, n, 0.95f) - percentile(cleanRed + s, n, 0.05f);
  const float aIrClean  = percentile(cleanIr + s, n, 0.95f)  - percentile(cleanIr + s, n, 0.05f);

  const float piRedRaw = (r.dcRed > 0) ? 100.0f * aRedRaw / r.dcRed : NAN;
  const float piIrRaw  = (r.dcIr  > 0) ? 100.0f * aIrRaw  / r.dcIr  : NAN;
  r.piRaw = 0.5f * (piRedRaw + piIrRaw);

  const float piRedClean = (r.dcRed > 0) ? 100.0f * aRedClean / r.dcRed : NAN;
  const float piIrClean  = (r.dcIr  > 0) ? 100.0f * aIrClean  / r.dcIr  : NAN;
  r.piClean = 0.5f * (piRedClean + piIrClean);

  r.aRed   = USE_DMACR_FOR_HYDRATION ? aRedClean : aRedRaw;
  r.aIr    = USE_DMACR_FOR_HYDRATION ? aIrClean  : aIrRaw;
  r.piRed  = USE_DMACR_FOR_HYDRATION ? piRedClean : piRedRaw;
  r.piIr   = USE_DMACR_FOR_HYDRATION ? piIrClean : piIrRaw;
  r.piComb = USE_DMACR_FOR_HYDRATION ? r.piClean : r.piRaw;
  r.rAC    = (r.aIr > 0) ? r.aRed / r.aIr : NAN;

  float m;
  meanSd(aMag + s, n, m, r.sdA);
  meanSd(gMag + s, n, m, r.sdG);

  const float MOTION_HARD_A_MAX = 0.50f;
  const float MOTION_HARD_G_MAX = 70.0f;
  const bool moving = (r.sdA > MOTION_HARD_A_MAX) || (r.sdG > MOTION_HARD_G_MAX);

  const int q = n / 4;
  double d1 = 0, d2 = 0;
  for (int i = 0; i < q; i++) { d1 += rawIr[s + i]; d2 += rawIr[e - 1 - i]; }
  const float drift = (r.dcIr > 0) ? fabsf((float)((d2 - d1) / q)) / r.dcIr : 1.0f;

  const float* pulse = USE_DMACR_FOR_HYDRATION ? cleanIr : fIr;
  const float thr = 0.15f * r.aIr;
  const int nPkP = findPeaks(pulse, s, e, +1.0f, thr, pkP, MAX_PEAKS);
  const int nPkN = findPeaks(pulse, s, e, -1.0f, thr, pkN, MAX_PEAKS);

  const int nvP = evalPeaks(pkP, nPkP, ibiP, valP);
  const int nvN = evalPeaks(pkN, nPkN, ibiN, valN);
  const bool useP = (nvP >= nvN);
  const float sign = useP ? 1.0f : -1.0f;
  const int*   pk  = useP ? pkP : pkN;
  const float* ibi = useP ? ibiP : ibiN;
  const bool*  val = useP ? valP : valN;
  const int nPk = useP ? nPkP : nPkN;
  const int nv  = useP ? nvP : nvN;
  const int ni  = nPk > 0 ? nPk - 1 : 0;
  r.nPeaks = nPk; r.nValid = nv;

  if (nv >= 2) {
    double sum = 0; int c = 0;
    for (int i = 0; i < ni; i++) if (val[i]) { sum += ibi[i]; c++; }
    if (c > 0) r.hr = 60.0f / (float)(sum / c);

    double ss = 0; int pairs = 0;
    for (int i = 0; i + 1 < ni; i++) {
      if (val[i] && val[i + 1]) { const double d = ibi[i + 1] - ibi[i]; ss += d * d; pairs++; }
    }
    if (pairs >= 2) r.rmssd = 1000.0f * (float)sqrt(ss / pairs);
  }

  int nb = 0;
  for (int k = 1; k < nPk - 1; k++) {
    if (!(val[k - 1] && val[k])) continue;
    const int foot  = argminS(pulse, sign, pk[k - 1], pk[k]);
    const int footN = argminS(pulse, sign, pk[k], pk[k + 1]);
    const float base = sign * pulse[foot];
    const float amp  = sign * pulse[pk[k]] - base;
    if (amp <= 0) continue;
    const float level = base + 0.5f * amp;
    int l = pk[k];  while (l > foot && sign * pulse[l] > level) l--;
    int rr = pk[k]; while (rr < footN && sign * pulse[rr] > level) rr++;
    float area = 0;
    for (int i = foot; i <= footN; i++) { const float v = sign * pulse[i] - base; if (v > 0) area += v; }
    amps[nb]   = amp;
    rises[nb]  = 1000.0f * (pk[k] - foot) / (float)FS;
    widths[nb] = 1000.0f * (rr - l) / (float)FS;
    areas[nb]  = area / (float)FS;
    nb++;
  }
  r.nBeats = nb;
  if (nb >= 3) {
    float am, asd;
    meanSd(amps, nb, am, asd);
    r.ampCV = (am > 0) ? asd / am : NAN;
    r.rise  = medianOf(rises, nb);
    r.width = medianOf(widths, nb);
    r.area  = medianOf(areas, nb);
  }

  // Signal Quality Index (SQI)
  const float qContact  = clamp01(r.contactFrac);
  const float validFrac = ni > 0 ? (float)nv / (float)ni : 0.0f;
  const float qPeaks    = clamp01(validFrac) * clamp01(nv / 4.0f);
  const float qStab     = isfinite(r.ampCV)
      ? 0.5f * (1.0f - clamp01(r.ampCV / 0.5f)) + 0.5f * (1.0f - clamp01(drift / 0.10f))
      : 0.0f;
  const float qMotion   = 1.0f - clamp01(fmaxf(r.sdA / (2.0f * MOTION_SD_A_MAX),
                                               r.sdG / (2.0f * MOTION_SD_G_MAX)));
  r.sqi = W_CONTACT * qContact + W_PEAKS * qPeaks + W_STAB * qStab + W_MOTION * qMotion;

  const bool finiteKey  = isfinite(r.piComb) && isfinite(r.hr) && isfinite(r.sqi);
  const bool postureBad = (r.postureDeg > POSTURE_CHANGE_MAX_DEG);

  if (satCount > 0)                                      r.status = ST_SATURATED;
  else if (r.contactFrac < 0.95f)                        r.status = ST_NO_CONTACT;
  else if (fabsf(r.fsMeas - FS) > FS_TOLERANCE * FS)     r.status = ST_RATE;
  else if (moving)                                       r.status = ST_MOTION;
  else if (r.sqi < SQI_MIN || nv < 4 || !finiteKey ||
           r.hr < HR_MIN || r.hr > HR_MAX)               r.status = ST_UNRELIABLE;
  else                                                   r.status = ST_OK;

  r.accepted = (r.status == ST_OK);

  if (postureBad) {
    postureRejectStreak++;
    r.status = ST_MOTION;
    r.accepted = false;
    Serial.printf("# Window rejected: posture tilt change %.1f deg (max %.1f deg).\n",
                  r.postureDeg, POSTURE_CHANGE_MAX_DEG);
    if (postureRejectStreak >= GATE_WARNING_REJECTS) {
      gateWarning = true;
      Serial.println("# WARNING: Wrist posture unstable. Keep arm relaxed on a flat surface.");
    }
    gateReady = false;
    postureBaselineValid = false;
    postureStableSince = 0;
    gateAttemptSince = millis();
  } else if (r.accepted) {
    postureRejectStreak = 0;
  }

  // Calibration or Measurement verdict
  if (r.accepted) {
    if (ref.calibrating) {
      ref.piBuf[ref.count] = r.piComb;
      ref.hrBuf[ref.count] = r.hr;
      ref.irBuf[ref.count] = r.dcIr;
      ref.count++;

      char calProg[160];
      snprintf(calProg, sizeof(calProg),
               "{\"type\":\"calibration\",\"progress\":%d,\"total\":%d,\"pi\":%.2f,\"hr\":%.1f,\"sqi\":%.3f}",
               ref.count, REF_WINDOWS, r.piComb, r.hr, r.sqi);
      sendBleJson(calProg);

      Serial.printf("# Calibration window %d of %d accepted (PI %.2f %%, HR %.0f)\n",
                    ref.count, REF_WINDOWS, r.piComb, r.hr);

      if (ref.count >= REF_WINDOWS) {
        float mu, sd;
        meanSd(ref.piBuf, REF_WINDOWS, mu, sd);
        const float cv = (mu > 0) ? 100.0f * sd / mu : 999.0f;
        if (cv > REF_MAX_CV_PCT) {
          Serial.printf("# Calibration scatter too high (%.1f %% > %.0f %%). Restarting.\n",
                        cv, REF_MAX_CV_PCT);
          ref.count = 0;
          sendBleJson("{\"type\":\"calibration\",\"progress\":0,\"total\":8,\"msg\":\"Restarting: noisy window\"}");
        } else {
          float t[REF_WINDOWS];
          memcpy(t, ref.piBuf, sizeof(t)); ref.refPI = medianOf(t, REF_WINDOWS);
          memcpy(t, ref.hrBuf, sizeof(t)); ref.refHR = medianOf(t, REF_WINDOWS);
          memcpy(t, ref.irBuf, sizeof(t)); ref.refIR = medianOf(t, REF_WINDOWS);
          ref.refCV = cv;
          ref.valid = true; ref.calibrating = false;
          clearSmoothing(); readingNo = 0;
          saveReference();

          char calComp[160];
          snprintf(calComp, sizeof(calComp),
                   "{\"type\":\"calibration_complete\",\"refPI\":%.2f,\"refHR\":%.1f,\"refIR\":%.0f,\"scatter\":%.1f}",
                   ref.refPI, ref.refHR, ref.refIR, ref.refCV);
          sendBleJson(calComp);

          Serial.println("\n# CALIBRATION COMPLETE (saved to flash)");
          Serial.printf("# Ref PI: %.2f %%, Ref HR: %.1f BPM, Ref IR: %.0f, Scatter: %.1f %%\n",
                        ref.refPI, ref.refHR, ref.refIR, ref.refCV);
        }
      }
    } else if (ref.valid) {
      r.irShift = 100.0f * (r.dcIr - ref.refIR) / ref.refIR;
      if (fabsf(r.irShift) > IR_SHIFT_MAX_PCT) {
        r.status = ST_REFIT;
        r.accepted = false;
        Serial.printf("# Fit shifted %+.0f %% vs calibration. Refit band.\n", r.irShift);
      } else {
        r.hi = 100.0f * r.piComb / ref.refPI;
        r.hiValid = isfinite(r.hi);

        hiRing[hiHead] = r.hi;
        hiHead = (hiHead + 1) % HI_SMOOTH_N;
        if (hiCount < HI_SMOOTH_N) hiCount++;

        float t[SMOOTH_MAX];
        memcpy(t, hiRing, sizeof(float) * hiCount);
        r.hiSmooth = medianOf(t, hiCount);
        r.margin   = marginBorder();
        r.hyd      = (hiCount >= HI_SMOOTH_MIN) ? hydOf(r.hiSmooth) : (uint8_t)HY_NONE;
        verdictHyd = r.hyd;
        verdictHI  = r.hiSmooth;
        r.reading  = ++readingNo;
        printReadingBlock(r);
      }
    } else {
      Serial.printf("# Window OK (PI %.2f %%) but no baseline - press BOOT or send 'c' to calibrate.\n",
                    r.piComb);
    }
  }

  // Serial Diagnostic Line (CSV format)
  Serial.printf("W,%d,%lu,%lu,%s,%.3f,%lu,%.1f,%.0f,%.0f,%.3f,%.3f,%.3f,%.3f,%.1f,%.1f,%.1f,%.1f,%.1f,%.3f,%.4f,%.2f,%.3f,%d,%d,%.1f,%.1f,%s,%.1f,%.1f\n",
                r.reading, (unsigned long)r.id, (unsigned long)r.tMs,
                STATUS_TXT[r.status], r.contactFrac, (unsigned long)r.sat, r.fsMeas,
                r.dcRed, r.dcIr, r.piRed, r.piIr, r.piComb, r.rAC,
                r.hr, r.rmssd, r.width, r.rise, r.area, r.ampCV,
                r.sdA, r.sdG, r.sqi, r.nValid, (int)r.accepted,
                r.hi, r.hiSmooth, HYD_SHORT[r.hyd], r.margin, r.irShift);

  // Send Complete BLE JSON Reading Packet
  char sHi[16], sHiSm[16], sPi[16], sHr[16], sRmssd[16], sSqi[16], sIr[16], sRed[16], sShift[16];
  char sRefPi[16], sRefHr[16], sRefIr[16], sRefCv[16];
  char sW[16], sRi[16], sAr[16], sAmpCv[16], sSdA[16], sSdG[16];

  formatFloatJson(sHi, sizeof(sHi), r.hi, 1);
  formatFloatJson(sHiSm, sizeof(sHiSm), r.hiSmooth, 1);
  formatFloatJson(sPi, sizeof(sPi), r.piComb, 2);
  formatFloatJson(sHr, sizeof(sHr), r.hr, 1);
  formatFloatJson(sRmssd, sizeof(sRmssd), r.rmssd, 1);
  formatFloatJson(sSqi, sizeof(sSqi), r.sqi, 3);
  formatFloatJson(sIr, sizeof(sIr), r.dcIr, 0);
  formatFloatJson(sRed, sizeof(sRed), r.dcRed, 0);
  formatFloatJson(sShift, sizeof(sShift), r.irShift, 1);
  formatFloatJson(sRefPi, sizeof(sRefPi), ref.refPI, 2);
  formatFloatJson(sRefHr, sizeof(sRefHr), ref.refHR, 1);
  formatFloatJson(sRefIr, sizeof(sRefIr), ref.refIR, 0);
  formatFloatJson(sRefCv, sizeof(sRefCv), ref.refCV, 1);
  formatFloatJson(sW, sizeof(sW), r.width, 1);
  formatFloatJson(sRi, sizeof(sRi), r.rise, 1);
  formatFloatJson(sAr, sizeof(sAr), r.area, 1);
  formatFloatJson(sAmpCv, sizeof(sAmpCv), r.ampCV, 3);
  formatFloatJson(sSdA, sizeof(sSdA), r.sdA, 4);
  formatFloatJson(sSdG, sizeof(sSdG), r.sdG, 2);

  const char* motionStr = (r.sdA > 0.05f || r.sdG > 10.0f) ? "MOVING" : "STABLE";
  const char* hydStatusStr = HYD_SHORT[r.hyd];
  const char* statusReport = (r.accepted && r.hyd != HY_NONE) ? hydStatusStr : STATUS_TXT[r.status];

  char jsonBuf[512];
  snprintf(jsonBuf, sizeof(jsonBuf),
    "{\"type\":\"reading\",\"reading\":%d,\"requestId\":%lu,\"accepted\":%s,\"status\":\"%s\","
    "\"hi\":%s,\"hiSmooth\":%s,\"pi\":%s,\"hr\":%s,\"rmssd\":%s,\"sqi\":%s,\"motion\":\"%s\","
    "\"ir\":%s,\"dcRed\":%s,\"irShift\":%s,\"contact\":%.3f,\"refPI\":%s,\"refHR\":%s,\"refIR\":%s,"
    "\"refCV\":%s,\"width\":%s,\"rise\":%s,\"area\":%s,\"ampCV\":%s,\"sdA\":%s,\"sdG\":%s,\"nValid\":%d}",
    r.reading, (unsigned long)r.requestId, r.accepted ? "true" : "false", statusReport,
    sHi, sHiSm, sPi, sHr, sRmssd, sSqi, motionStr,
    sIr, sRed, sShift, r.contactFrac, sRefPi, sRefHr, sRefIr,
    sRefCv, sW, sRi, sAr, sAmpCv, sSdA, sSdG, r.nValid
  );

  sendBleJson(jsonBuf);

  // On-demand cycle completed
  if (onDemandMeasuring) {
    onDemandMeasuring = false;
    Serial.printf("# On-demand measurement #%lu finished (accepted=%d)\n",
                  (unsigned long)onDemandRequestId, (int)r.accepted);
  }

  lastR = r;
  haveR = true;
  firstWindow = false;
}

// =====================================================================
// SAMPLE INGEST & LMS FILTERING
// =====================================================================
static void ingestSample(uint32_t red, uint32_t ir) {
  lastRed = red; lastIr = ir;
  const bool good = (ir > CONTACT_IR_THRESH);

  // Contact Detection
  if (!inContact) {
    goodRun = good ? goodRun + 1 : 0;
    if (goodRun < 5) return;

    inContact = true;
    firstWindow = true;
    haveR = false;
    lostRun = 0;
    gateReady = false;
    postureStableSince = 0;
    gateAttemptSince = millis();
    gateRejectStreak = 0;
    gateWarning = false;
    postureRejectStreak = 0;
    postureBaselineValid = false;
    clearWindow();

    if (millis() - contactLostMs > 30000UL) clearSmoothing();
    resetFilters((float)red, (float)ir);
    Serial.println("# Contact detected. Keep wrist still and comfortable.");
  }

  if (!good) {
    if (++lostRun >= 5) { dropContact(); return; }
  } else {
    lostRun = 0;
  }

  // Phase 1 Activity/Posture Gate
  if (!gateReady) {
    const bool stableEnough = (aNow >= 0.65f && aNow <= 1.35f && gNow <= 30.0f);

    if (good && stableEnough) {
      if (postureStableSince == 0) postureStableSince = millis();

      if (millis() - postureStableSince >= POSTURE_STABLE_MS) {
        const float mag = fmaxf(aNow, 0.001f);
        baseAx = axNow / mag;
        baseAy = ayNow / mag;
        baseAz = azNow / mag;
        postureBaselineValid = true;
        postureAngleDeg = 0.0f;
        gateReady = true;
        gateAttemptSince = millis();
        gateRejectStreak = 0;
        gateWarning = false;
        clearWindow();
        resetFilters((float)red, (float)ir);
        Serial.println("# Measurement gate READY. Wrist baseline captured. Starting 15 s window.");
      }
    } else {
      postureStableSince = 0;
      if (millis() - gateAttemptSince >= GATE_RETRY_MS) {
        gateAttemptSince = millis();
        gateRejectStreak++;
        Serial.printf("# Gate not ready: motion/orientation unstable (accel %.3f g, gyro %.1f dps). Attempt %d/%d.\n",
                      aNow, gNow, gateRejectStreak, GATE_WARNING_REJECTS);
        if (gateRejectStreak >= GATE_WARNING_REJECTS) {
          gateWarning = true;
          Serial.println("# WARNING: Wrist moved repeatedly. Reposition wrist and hold steady.");
        }
      }
    }
    return;
  }

  // Track max posture deviation in window
  if (postureAngleDeg > postureMaxWindowDeg) postureMaxWindowDeg = postureAngleDeg;
  if (idx == 0) winStartMs = millis();
  if (red >= SATURATION_LEVEL || ir >= SATURATION_LEVEL) satCount++;
  if (!good) badContact++;

  rawRed[idx] = red;
  rawIr[idx]  = ir;
  aMag[idx]   = aNow;
  gMag[idx]   = gNow;

  fRed[idx] = lpR.process(hpR.process((float)red - dcOffR));
  fIr[idx]  = lpI.process(hpI.process((float)ir  - dcOffI));

  // Multi-axis LMS motion artifact cleaning
  const float motionRef[LMS_AXES] = { axNow, ayNow, azNow, gxNow, gyNow, gzNow };
  cleanRed[idx] = lmsRed.process(fRed[idx], motionRef);
  cleanIr[idx]  = lmsIr.process(fIr[idx], motionRef);

  if (streamRaw) {
    Serial.printf("R,%lu,%lu,%lu,%.3f,%.2f,%.1f\n",
                  (unsigned long)millis(), (unsigned long)red, (unsigned long)ir,
                  aNow, gNow, postureAngleDeg);
  }

  idx++;
  if (idx >= N_WIN) {
    processWindow();
    clearWindow();
  }
}

// =====================================================================
// COMMAND DISPATCHER & MEASURE NOW LOGIC
// =====================================================================
static void startOnDemandMeasurement() {
  if (!ppgOk) {
    sendBleJson("{\"type\":\"measure_error\",\"status\":\"UNRELIABLE\",\"reason\":\"PPG sensor initialization failed on GPIO 4/5\"}");
    return;
  }
  if (!inContact) {
    sendBleJson("{\"type\":\"measure_error\",\"status\":\"WEAR_BAND\",\"reason\":\"Sensor contact lost. Wear band snugly.\"}");
    return;
  }
  if (!ref.valid) {
    sendBleJson("{\"type\":\"measure_error\",\"status\":\"UNRELIABLE\",\"reason\":\"Personal baseline not calibrated. Calibrate first while hydrated.\"}");
    return;
  }
  if (ref.calibrating) {
    sendBleJson("{\"type\":\"measure_error\",\"status\":\"UNRELIABLE\",\"reason\":\"Baseline calibration in progress. Wait for completion.\"}");
    return;
  }
  if (onDemandMeasuring) {
    sendBleJson("{\"type\":\"status\",\"status\":\"MEASURING\",\"msg\":\"Measurement already in progress\"}");
    return;
  }

  onDemandMeasuring = true;
  onDemandRequestId++;
  onDemandStartMs = millis();
  lastMeasureProgMs = onDemandStartMs;

  clearWindow();
  resetFilters((float)lastRed, (float)lastIr);
  winStartMs = millis();

  // Reset posture baseline for this fresh reading
  if (aNow > 0.001f) {
    baseAx = axNow / aNow;
    baseAy = ayNow / aNow;
    baseAz = azNow / aNow;
    postureBaselineValid = true;
    postureAngleDeg = 0.0f;
  }

  char startMsg[128];
  snprintf(startMsg, sizeof(startMsg),
           "{\"type\":\"measure_start\",\"duration\":%d,\"requestId\":%lu}",
           WINDOW_SEC, (unsigned long)onDemandRequestId);
  sendBleJson(startMsg);

  Serial.printf("# ON-DEMAND MEASURE TRIGGERED (Request #%lu, %ds cycle)\n",
                (unsigned long)onDemandRequestId, WINDOW_SEC);
}

static void printStatus() {
  if (ref.valid) {
    Serial.printf("# Reference: PI %.2f %% (scatter %.1f %%), HR %.1f, IR %.0f\n",
                  ref.refPI, ref.refCV, ref.refHR, ref.refIR);
    Serial.printf("# Thresholds: HYDRATED HI >= %.0f, BORDERLINE >= %.0f, DEHYDRATED below\n",
                  100.0f - marginBorder(), 100.0f - marginLow());
  } else if (ref.calibrating) {
    Serial.printf("# Calibrating: %d of %d windows\n", ref.count, REF_WINDOWS);
  } else {
    Serial.println("# No reference. Calibrate first (BOOT or 'c').");
  }

  Serial.printf("# Phase 1 gate: %s, wrist-change %.1f deg, warning=%s\n",
                gateReady ? "READY" : "WAITING", postureAngleDeg,
                gateWarning ? "YES" : "NO");

  if (ref.valid && hiCount >= HI_SMOOTH_MIN && verdictHyd != HY_NONE) {
    Serial.printf("# Last result: HI %.1f -> %s%s\n", verdictHI, HYD_LONG[verdictHyd],
                  (hiCount < HI_SMOOTH_N) ? " [preliminary]" : "");
  }

  char statJson[256];
  snprintf(statJson, sizeof(statJson),
    "{\"type\":\"status\",\"device\":\"" BLE_DEVICE_NAME "\",\"calibrated\":%s,\"refPI\":%.2f,"
    "\"refHR\":%.1f,\"refIR\":%.0f,\"scatter\":%.1f,\"inContact\":%s,\"gateReady\":%s}",
    ref.valid ? "true" : "false", ref.refPI, ref.refHR, ref.refIR, ref.refCV,
    inContact ? "true" : "false", gateReady ? "true" : "false"
  );
  sendBleJson(statJson);
}

static void printHelp() {
  Serial.println("# HydroSense Edge - Controls:");
  Serial.println("#   BOOT Button: short = calibrate, hold 2s = clear reference");
  Serial.println("#   Serial/BLE Commands:");
  Serial.println("#     m = Measure Now (trigger fresh on-demand 15s cycle)");
  Serial.println("#     c = Calibrate baseline (8 windows while hydrated)");
  Serial.println("#     x = Clear saved reference");
  Serial.println("#     s = Report device status");
  Serial.println("#     p = Toggle raw sample stream");
  Serial.println("#     h = Help");
}

static void handleCommand(char c) {
  if (c == 'm' || c == 'M') startOnDemandMeasurement();
  else if (c == 'c' || c == 'C') startCalibration();
  else if (c == 'x' || c == 'X') clearReference();
  else if (c == 's' || c == 'S') printStatus();
  else if (c == 'p' || c == 'P') { streamRaw = !streamRaw; Serial.printf("# raw stream %s\n", streamRaw ? "ON" : "OFF"); }
  else if (c == 'h' || c == 'H') printHelp();
}

static void handleSerial() {
  while (Serial.available()) {
    const char c = (char)Serial.read();
    handleCommand(c);
  }
}

static void handleButton() {
  const bool down = (digitalRead(PIN_BUTTON) == LOW);
  const uint32_t now = millis();
  if (down && !btnDown) {
    btnDown = true; btnMs = now; btnLongDone = false;
  } else if (down && btnDown && !btnLongDone && (now - btnMs) >= 2000) {
    btnLongDone = true;
    clearReference();
  } else if (!down && btnDown) {
    if (!btnLongDone && (now - btnMs) > 50) startCalibration();
    btnDown = false;
  }
}

// =====================================================================
// OLED DISPLAY
// =====================================================================
static void drawHeadline(const char* s) {
  const int sz = ((int)strlen(s) * 12 <= 128) ? 2 : 1;
  display.setTextSize(sz);
  display.setCursor(0, 0);
  display.print(s);
  display.setTextSize(1);
}

static void updateDisplay() {
  if (!oledOk) return;
  if (millis() - lastDispMs < 250) return;
  lastDispMs = millis();

  char buf[32], head[24];
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);

  const bool okNow = haveR && lastR.accepted;

  if (gateWarning && !gateReady) {
    drawHeadline("CHECK WRIST");
    display.setCursor(0, 18); display.print("Position shifted");
    display.setCursor(0, 27); display.print("Signal may scatter");
    display.setCursor(0, 36); display.print("Hold still on desk");
    display.setCursor(0, 45); snprintf(buf, sizeof(buf), "Tilt: %.0f deg", postureAngleDeg); display.print(buf);
    display.setCursor(0, 54); display.print("Waiting for stability");
    display.display();
    return;
  }

  if (ref.calibrating)              snprintf(head, sizeof(head), "CAL %d/%d", ref.count, REF_WINDOWS);
  else if (onDemandMeasuring)       snprintf(head, sizeof(head), "MEASURING %d%%", (int)(100L * idx / N_WIN));
  else if (!inContact)              snprintf(head, sizeof(head), "WEAR BAND");
  else if (!gateReady)              snprintf(head, sizeof(head), "HOLD STILL");
  else if (!haveR)                  snprintf(head, sizeof(head), "MEASURING");
  else if (!okNow)                  snprintf(head, sizeof(head), "%s", HEAD_TXT[lastR.status]);
  else if (!ref.valid)              snprintf(head, sizeof(head), "CALIBRATE");
  else if (hiCount < HI_SMOOTH_MIN) snprintf(head, sizeof(head), "MEASURING");
  else                              snprintf(head, sizeof(head), "%s", HYD_SHORT[lastR.hyd]);
  drawHeadline(head);

  display.setCursor(0, 18);
  if (okNow && ref.valid && lastR.hiValid)
    snprintf(buf, sizeof(buf), "HI:%.1f%s HR:%.0f", lastR.hiSmooth,
             (hiCount >= HI_SMOOTH_MIN && hiCount < HI_SMOOTH_N) ? "?" : " ", lastR.hr);
  else if (okNow)
    snprintf(buf, sizeof(buf), "HI:--   HR:%.0f", lastR.hr);
  else
    snprintf(buf, sizeof(buf), "HI:--   HR:--");
  display.print(buf);

  display.setCursor(0, 27);
  if (okNow) snprintf(buf, sizeof(buf), "PI:%.2f%% RMSSD:%.0f", lastR.piComb, lastR.rmssd);
  else       snprintf(buf, sizeof(buf), "PI:-- RMSSD:--");
  display.print(buf);

  display.setCursor(0, 36);
  if (onDemandMeasuring)
    snprintf(buf, sizeof(buf), "Window %d%% T:%.0fs", (int)(100L * idx / N_WIN), (float)(millis() - onDemandStartMs) / 1000.0f);
  else if (gateReady && !haveR)
    snprintf(buf, sizeof(buf), "Sampling %d%% C%.0f", (int)(100L * idx / N_WIN), postureAngleDeg);
  else if (haveR && inContact)
    snprintf(buf, sizeof(buf), "SQI:%.2f Tilt:%.0f", lastR.sqi, lastR.postureDeg);
  else if (inContact)
    snprintf(buf, sizeof(buf), "Hold: G%.0f", gNow);
  else
    snprintf(buf, sizeof(buf), "SQI:--");
  display.print(buf);

  display.setCursor(0, 45);
  if (ref.calibrating)      snprintf(buf, sizeof(buf), "Stay still, relaxed");
  else if (ref.valid)       snprintf(buf, sizeof(buf), "Ref PI:%.2f%% sc:%.0f%%", ref.refPI, ref.refCV);
  else                      snprintf(buf, sizeof(buf), "No ref: press BOOT");
  display.print(buf);

  display.setCursor(0, 54);
  if (deviceConnected)
    snprintf(buf, sizeof(buf), "BLE: CONNECTED");
  else
    snprintf(buf, sizeof(buf), "BLE: ADV (HydroSense)");
  display.print(buf);

  display.display();
}

// =====================================================================
// SETUP & INITIALIZATION
// =====================================================================
void setup() {
  Serial.begin(115200);
  const uint32_t t0 = millis();
  while (!Serial && (millis() - t0) < 3000) delay(10);
  delay(200);
  Serial.println("\n# ==================================================");
  Serial.println("# HydroSense Edge — ESP32-S3 Phase 1 Final Firmware");
  Serial.println("# Optical PPG Hydration Monitor (Prototype)");
  Serial.println("# ==================================================");

  pinMode(PIN_BUTTON, INPUT_PULLUP);

  // Initialize Dual I2C Buses
  Wire.begin(PIN_SDA_PPG, PIN_SCL_PPG);
  Wire.setClock(I2C_HZ);
  Wire1.begin(PIN_SDA_AUX, PIN_SCL_AUX);
  Wire1.setClock(I2C_HZ);

  i2cScan(Wire,  "MAX30102 bus, GPIO 4/5", false);
  i2cScan(Wire1, "MPU6050+OLED bus, GPIO 8/9", true);

  // Initialize SSD1306 OLED
  oledOk = display.begin(SSD1306_SWITCHCAPVCC, oledAddr, true, false);
  if (oledOk) {
    display.clearDisplay();
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(0, 0);
    display.println("HydroSense Edge");
    display.println("Initializing BLE...");
    display.display();
  } else {
    Serial.println("# OLED not found on Wire1 (0x3C)");
  }

  // Initialize MPU6050 IMU
  mpuOk = mpuInit();
  if (!mpuOk) {
    Serial.println("# MPU6050 not found on Wire1 (0x68). Motion rejection disabled!");
  }

  // Initialize MAX30102 PPG
  for (int attempt = 0; attempt < 5 && !ppgOk; attempt++) {
    ppgOk = ppg.begin(Wire, I2C_SPEED_FAST);
    if (!ppgOk) { Serial.println("# MAX30102 init retry..."); delay(400); }
  }
  if (!ppgOk) {
    Serial.println("# MAX30102 FAILED on GPIO 4/5! Halting.");
    if (oledOk) {
      display.clearDisplay(); display.setCursor(0, 0);
      display.println("MAX30102 ERROR");
      display.println("Check GPIO 4/5 wiring");
      display.display();
    }
    while (true) delay(1000);
  }

  ppg.setup(LED_BRIGHTNESS, 1, 2, FS, 411, 16384);
  ppg.clearFIFO();

  // Load Saved Personal Baseline from Flash
  loadReference();

  // Initialize BLE GATT Server
  Serial.println("# Initializing Bluetooth Low Energy...");
  BLEDevice::init(BLE_DEVICE_NAME);
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new HydroServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);
  pCharacteristic = pService->createCharacteristic(
    CHAR_UUID,
    BLECharacteristic::PROPERTY_READ   |
    BLECharacteristic::PROPERTY_WRITE  |
    BLECharacteristic::PROPERTY_NOTIFY
  );
  pCharacteristic->addDescriptor(new BLE2902());
  pCharacteristic->setCallbacks(new HydroCharacteristicCallbacks());

  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);
  pAdvertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println("# BLE Advertising started: 'HydroSense Edge'");

  printHelp();
  if (ref.valid) {
    Serial.println("# Personal baseline loaded:");
    printStatus();
  } else {
    Serial.println("# No saved baseline. Calibrate while well-hydrated (press BOOT or 'c').");
  }
}

// =====================================================================
// MAIN LOOP
// =====================================================================
void loop() {
  handleSerial();
  handleButton();

  const uint32_t now = millis();

  // 100 Hz MPU6050 Poll
  if (mpuOk && (now - lastMpuMs) >= 10) {
    lastMpuMs = now;
    mpuRead();
  }

  // PPG FIFO Read
  ppg.check();
  while (ppg.available()) {
    const uint32_t red = ppg.getFIFORed();
    const uint32_t ir  = ppg.getFIFOIR();
    ppg.nextSample();
    ingestSample(red, ir);
  }

  // Periodic On-Demand Measurement Progress Telemetry (1 Hz)
  if (onDemandMeasuring && (now - lastMeasureProgMs >= 1000)) {
    lastMeasureProgMs = now;
    int pct = constrain((int)((100L * idx) / N_WIN), 0, 99);
    int secLeft = constrain(WINDOW_SEC - (int)((now - onDemandStartMs) / 1000), 1, WINDOW_SEC);

    const char* stTxt = "MEASURING";
    if (!inContact) stTxt = "WEAR BAND";
    else if (postureAngleDeg > 20.0f) stTxt = "HOLD STILL";
    else if (idx < SETTLE) stTxt = "STABILIZING";
    else stTxt = "ANALYZING";

    char progJson[160];
    snprintf(progJson, sizeof(progJson),
             "{\"type\":\"measure_progress\",\"percent\":%d,\"secondsRemaining\":%d,\"status\":\"%s\",\"angle\":%.1f,\"requestId\":%lu}",
             pct, secLeft, stTxt, postureAngleDeg, (unsigned long)onDemandRequestId);
    sendBleJson(progJson);
  }

  // Periodic 1 Hz Heartbeat / Debug Log
  if (!streamRaw && (now - lastLiveMs) >= 1000) {
    lastLiveMs = now;
    Serial.printf("# live RED=%lu IR=%lu worn=%d gate=%d onDemand=%d idx=%d tilt=%.1f accel=%.3f gyro=%.1f\n",
                  (unsigned long)lastRed, (unsigned long)lastIr, (int)inContact,
                  (int)gateReady, (int)onDemandMeasuring, idx, postureAngleDeg, aNow, gNow);
  }

  updateDisplay();
  delay(1);
}
