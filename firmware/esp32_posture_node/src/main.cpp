/* SpineCare posture node — ESP32-C3 + BMI270 6-DOF IMU over BLE.
 *
 * Hardware: ESP32-C3 SuperMini + BMI270 breakout (I2C: SDA=GPIO6, SCL=GPIO7,
 * VCC=3.3V, GND). Mount rigidly on the head strap (C7 occiput band) or upper
 * trunk (T1-T4 interscapular band) — one node per location, NODE_ID selects role.
 *
 * BLE GATT: service 0xFFF0
 *   0xFFF1 head IMU   (notify, 20 bytes)
 *   0xFFF2 trunk IMU  (notify, 20 bytes)
 *   0xFFF3 control    (write: 0=calibrate neutral, 1=zero yaw)
 * Payload (little-endian): seq u16, pitch i16(0.01deg), roll i16, yaw i16,
 *   ax i16(mg), ay i16, az i16, gx i16(0.1dps), gy i16, gz i16.
 *
 * Orientation fusion: complementary filter (accel low-pass + gyro integration)
 * — drift-free enough for posture monitoring at 50 Hz. For research-grade use
 * Madgwick/Mahony (see spine-system-blueprint.md).
 *
 * SAFETY: measures the SENSOR HOUSING orientation on skin/clothing. It estimates
 * body-segment tilt; it does NOT measure vertebral positions or spinal pathology.
 */
#include <Wire.h>
#include <math.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include "SparkFun_BMI270_Arduino_Library.h"

// ---- config ----
#define NODE_ID        1        // 1 = head node, 2 = trunk node
#define SDA_PIN        6
#define SCL_PIN        7
#define SAMPLE_HZ      50
#define ALPHA          0.98f    // complementary: accel weight

// ---- state ----
float pitch = 0, roll = 0, yaw = 0;
float gx = 0, gy = 0, gz = 0;
int16_t ax = 0, ay = 0, az = 0;
uint16_t seq = 0;
bool calibrated = false;
float yawOffset = 0;

BMI270 imu;
bool imuOk = false;

BLECharacteristic *chrHead, *chrTrunk, *chrCtrl;

class OnWriteCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    std::string val = c->getValue();
    if (val.empty()) return;                     // empty write: ignore
    uint8_t v = (uint8_t)val[0];
    if (v == 0) { calibrated = false; }          // re-run neutral calibration
    if (v == 1) { yawOffset = yaw; }             // zero yaw at current heading
  }
};

// Restart advertising after a central disconnects, otherwise the node
// becomes undiscoverable until it is power-cycled.
class ServerCB : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {}
  void onDisconnect(BLEServer *s) override { BLEDevice::startAdvertising(); }
};

void setup() {
  Serial.begin(115200);
  Wire.begin(SDA_PIN, SCL_PIN);
  for (int i = 0; i < 5 && !imuOk; i++) {
    imuOk = imu.beginI2C(BMI2_I2C_PRIM_ADDR, Wire) == BMI2_OK;
    if (!imuOk) { Serial.println("BMI270 not found - check I2C wiring"); delay(500); }
  }

  BLEDevice::init(NODE_ID == 1 ? "SpineCare-HEAD" : "SpineCare-TRUNK");
  BLEServer *srv = BLEDevice::createServer();
  srv->setCallbacks(new ServerCB());
  BLEService *svc = srv->createService("0000fff0-0000-1000-8000-00805f9b34fb");
  chrHead  = svc->createCharacteristic("0000fff1-0000-1000-8000-00805f9b34fb", BLECharacteristic::PROPERTY_NOTIFY);
  chrTrunk = svc->createCharacteristic("0000fff2-0000-1000-8000-00805f9b34fb", BLECharacteristic::PROPERTY_NOTIFY);
  chrCtrl  = svc->createCharacteristic("0000fff3-0000-1000-8000-00805f9b34fb", BLECharacteristic::PROPERTY_WRITE);
  chrHead->addDescriptor(new BLE2902());
  chrTrunk->addDescriptor(new BLE2902());
  chrCtrl->setCallbacks(new OnWriteCB());
  svc->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID("0000fff0-0000-1000-8000-00805f9b34fb");
  adv->start();
  Serial.println("BLE advertising as posture node");
}

void loop() {
  static uint32_t last = 0;
  uint32_t now = millis();
  if (last == 0) { last = now; return; }        // first pass: no dt spike
  if (now - last < 1000 / SAMPLE_HZ) return;
  float dt = (now - last) / 1000.0f; last = now;
  if (!imuOk) return;

  // read IMU (SparkFun lib: accel in g, gyro in deg/s)
  if (imu.getSensorData() != BMI2_OK) return;
  ax = (int16_t)(imu.data.accelX * 1000);   // g -> mg
  ay = (int16_t)(imu.data.accelY * 1000);
  az = (int16_t)(imu.data.accelZ * 1000);
  gx = imu.data.gyroX; gy = imu.data.gyroY; gz = imu.data.gyroZ;

  // tilt from accel
  float aPitch = atan2f(-ax, sqrtf(ay * 1.0f * ay + az * 1.0f * az)) * 57.29578f;
  float aRoll  = atan2f(ay, az) * 57.29578f;

  if (!calibrated) {  // neutral posture calibration: assume current pose = 0
    pitch = aPitch; roll = aRoll; yaw = 0; calibrated = true; yawOffset = 0;
  }

  // complementary fusion
  // pitch (atan2(-ax, ...)) rotates about Y, roll (atan2(ay, az)) about X.
  pitch = ALPHA * (pitch + gy * dt) + (1 - ALPHA) * aPitch;
  roll  = ALPHA * (roll  + gx * dt) + (1 - ALPHA) * aRoll;
  yaw   = yaw + gz * dt;

  float outPitch = pitch, outRoll = roll, outYaw = yaw - yawOffset;

  // pack payload
  uint8_t buf[20];
  auto put16 = [&](int off, int16_t v) { buf[off] = v & 0xFF; buf[off + 1] = (v >> 8) & 0xFF; };
  put16(0, (int16_t)seq++);
  put16(2, (int16_t)(outPitch * 100));
  put16(4, (int16_t)(outRoll * 100));
  put16(6, (int16_t)(outYaw * 100));
  put16(8, ax); put16(10, ay); put16(12, az);
  put16(14, (int16_t)(gx * 10)); put16(16, (int16_t)(gy * 10)); put16(18, (int16_t)(gz * 10));

  BLECharacteristic *target = (NODE_ID == 1) ? chrHead : chrTrunk;
  target->setValue(buf, sizeof(buf));
  target->notify();

  if (seq % 50 == 0)
    Serial.printf("pitch=%.1f roll=%.1f yaw=%.1f\n", outPitch, outRoll, outYaw);
}
