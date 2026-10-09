/*
 * EEPROM Bridge firmware for EEPROM Pocket (Android)
 * Board : Arduino Nano / UNO / Pro Mini (ATmega328P) – also works on most Arduino-compatible boards
 * Serial: 1,000,000 baud
 *
 * Wiring
 *   I2C  (24xx): A4 = SDA, A5 = SCL  (4.7k pull-ups to VCC)
 *   SPI  (25xx): D10 = CS, D11 = MOSI, D12 = MISO, D13 = SCK
 *   MW   (93xx): D10 = CS (active high), D13 = SK, D11 = DI, D12 = DO
 *
 * Protocol
 *   host -> dev : A5 cmd lenL lenH payload[len]
 *   dev  -> host: 5A status lenL lenH data[len]
 */
#include <Wire.h>
#include <SPI.h>

#define CS_PIN   10
#define MOSI_PIN 11
#define MISO_PIN 12
#define SCK_PIN  13

static uint8_t buf[300];
static uint8_t out[300];

enum { M_NONE, M_SPI, M_MW };
static uint8_t mode = M_NONE;
static bool spiInTxn = false;

static bool readBytes(uint8_t *p, uint16_t n) {
  unsigned long t = millis();
  uint16_t i = 0;
  while (i < n) {
    if (Serial.available()) { p[i++] = Serial.read(); t = millis(); }
    else if (millis() - t > 500) return false;
  }
  return true;
}

static void reply(uint8_t st, const uint8_t *d, uint16_t n) {
  Serial.write(0x5A);
  Serial.write(st);
  Serial.write((uint8_t)(n & 0xFF));
  Serial.write((uint8_t)(n >> 8));
  if (n) Serial.write(d, n);
}

static void spiMode() {
  if (mode == M_SPI) return;
  digitalWrite(CS_PIN, HIGH);
  pinMode(CS_PIN, OUTPUT);
  SPI.begin();
  mode = M_SPI;
}

static void mwMode() {
  if (mode == M_MW) return;
  if (mode == M_SPI) { if (spiInTxn) { SPI.endTransaction(); spiInTxn = false; } SPI.end(); }
  pinMode(CS_PIN, OUTPUT);   digitalWrite(CS_PIN, LOW);
  pinMode(SCK_PIN, OUTPUT);  digitalWrite(SCK_PIN, LOW);
  pinMode(MOSI_PIN, OUTPUT); digitalWrite(MOSI_PIN, LOW);
  pinMode(MISO_PIN, INPUT);
  mode = M_MW;
}

void setup() {
  Serial.begin(1000000);
  pinMode(CS_PIN, OUTPUT);
  digitalWrite(CS_PIN, HIGH);
  Wire.begin();
  Wire.setClock(100000);
}

void loop() {
  if (!Serial.available()) return;
  if (Serial.read() != 0xA5) return;
  uint8_t h[3];
  if (!readBytes(h, 3)) return;
  uint8_t cmd = h[0];
  uint16_t len = h[1] | ((uint16_t)h[2] << 8);
  if (len > sizeof(buf)) {
    uint8_t dummy;
    for (uint16_t i = 0; i < len; i++) if (!readBytes(&dummy, 1)) break;
    reply(0xFE, 0, 0);
    return;
  }
  if (len && !readBytes(buf, len)) return;

  switch (cmd) {
    case 0x01: {                                   // PING
      static const uint8_t id[] = { 'E', 'E', 'P', 'B', '1' };
      reply(0, id, sizeof(id));
      break;
    }
    case 0x02: {                                   // I2C speed (kHz)
      uint16_t khz = buf[0] | (buf[1] << 8);
      Wire.setClock((uint32_t)khz * 1000UL);
      reply(0, 0, 0);
      break;
    }
    case 0x10: {                                   // I2C write: addr, data...
      Wire.beginTransmission(buf[0]);
      if (len > 1) Wire.write(buf + 1, len - 1);
      uint8_t st = Wire.endTransmission();
      reply(st == 0 ? 0 : 1, 0, 0);
      break;
    }
    case 0x11: {                                   // I2C write-then-read: addr, rlen, wdata...
      uint8_t addr = buf[0], rlen = buf[1];
      uint16_t wlen = len - 2;
      if (wlen) {
        Wire.beginTransmission(addr);
        Wire.write(buf + 2, wlen);
        if (Wire.endTransmission(false) != 0) { reply(1, 0, 0); break; }
      }
      uint8_t n = Wire.requestFrom((int)addr, (int)rlen);
      for (uint8_t i = 0; i < n; i++) out[i] = Wire.read();
      reply(n == rlen ? 0 : 1, out, n);
      break;
    }
    case 0x20: {                                   // SPI: flags(bit1 = CS start, bit0 = CS end), data...
      spiMode();
      uint8_t flags = buf[0];
      uint16_t n = len - 1;
      if (flags & 2) {
        if (spiInTxn) SPI.endTransaction();
        SPI.beginTransaction(SPISettings(4000000, MSBFIRST, SPI_MODE0));
        spiInTxn = true;
        digitalWrite(CS_PIN, LOW);
      }
      for (uint16_t i = 0; i < n; i++) out[i] = SPI.transfer(buf[1 + i]);
      if (flags & 1) {
        digitalWrite(CS_PIN, HIGH);
        SPI.endTransaction();
        spiInTxn = false;
      }
      reply(0, out, n);
      break;
    }
    case 0x30: {                                   // Microwire: wbits(2), rbits(2), wdata...
      mwMode();
      uint16_t wbits = buf[0] | (buf[1] << 8);
      uint16_t rbits = buf[2] | (buf[3] << 8);
      const uint8_t *d = buf + 4;
      uint16_t rbytes = (rbits + 7) / 8;
      if (rbytes > sizeof(out)) { reply(0xFD, 0, 0); break; }
      digitalWrite(CS_PIN, HIGH);
      delayMicroseconds(1);
      for (uint16_t i = 0; i < wbits; i++) {
        digitalWrite(MOSI_PIN, (d[i >> 3] >> (7 - (i & 7))) & 1);
        delayMicroseconds(1);
        digitalWrite(SCK_PIN, HIGH);
        delayMicroseconds(1);
        digitalWrite(SCK_PIN, LOW);
      }
      digitalWrite(MOSI_PIN, LOW);
      memset(out, 0, rbytes);
      for (uint16_t i = 0; i < rbits; i++) {
        digitalWrite(SCK_PIN, HIGH);
        delayMicroseconds(1);
        digitalWrite(SCK_PIN, LOW);
        delayMicroseconds(1);
        if (digitalRead(MISO_PIN)) out[i >> 3] |= (0x80 >> (i & 7));
      }
      digitalWrite(CS_PIN, LOW);
      reply(0, out, rbytes);
      break;
    }
    case 0x31: {                                   // Microwire wait ready: timeout ms (2)
      mwMode();
      uint16_t to = buf[0] | (buf[1] << 8);
      digitalWrite(CS_PIN, LOW);
      delayMicroseconds(2);
      digitalWrite(CS_PIN, HIGH);
      delayMicroseconds(2);
      unsigned long t = millis();
      bool ok = false;
      while (millis() - t <= to) { if (digitalRead(MISO_PIN)) { ok = true; break; } }
      digitalWrite(CS_PIN, LOW);
      reply(ok ? 0 : 2, 0, 0);
      break;
    }
    default:
      reply(0xFF, 0, 0);
  }
}
